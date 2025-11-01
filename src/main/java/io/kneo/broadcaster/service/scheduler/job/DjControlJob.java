package io.kneo.broadcaster.service.scheduler.job;

import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.model.cnst.EventType;
import io.kneo.broadcaster.model.cnst.MemoryType;
import io.kneo.broadcaster.service.MemoryService;
import io.kneo.broadcaster.service.stream.RadioStationPool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class DjControlJob implements Job {
    private static final Logger LOGGER = LoggerFactory.getLogger(DjControlJob.class);

    @Inject
    RadioStationPool radioStationPool;

    @Inject
    MemoryService memoryService;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        String stationSlugName = context.getJobDetail().getJobDataMap().getString("stationSlugName");
        String action = context.getJobDetail().getJobDataMap().getString("action");
        String target = context.getJobDetail().getJobDataMap().getString("target");

        LOGGER.info("Executing DJ control job for station: {} with action: {}", stationSlugName, action);

        try {
            switch (action) {
                case "START" -> startDjControl(stationSlugName, target);
                case "STOP" -> stopDjControl(stationSlugName);
                case "WARNING" -> sendDjWarning(stationSlugName);
                default -> LOGGER.warn("Unknown DJ control action: {}", action);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to execute DJ control job for station: {}", stationSlugName, e);
            throw new JobExecutionException(e);
        }
    }

    private void startDjControl(String brand, String target) {
        radioStationPool.getOnlineStationsSnapshot()
                .stream()
                .filter(station -> station.getSlugName().equals(brand))
                .filter(station -> !station.isAiControlAllowed())
                .forEach(station -> {
                    station.setAiControlAllowed(true);
                    createEventMemory(brand, EventType.SHIFT_STARTED, "The shift of the dj started");
                    LOGGER.info("Started DJ control for station: {} with target: {}", brand, target);
                });
    }

    private void stopDjControl(String brand) {
        radioStationPool.getOnlineStationsSnapshot()
                .stream()
                .filter(station -> station.getSlugName().equals(brand))
                .filter(station ->
                        station.getStatus() == RadioStationStatus.ON_LINE ||
                                station.getStatus() == RadioStationStatus.WARMING_UP ||
                                station.getStatus() == RadioStationStatus.QUEUE_SATURATED ||
                                station.getStatus() == RadioStationStatus.IDLE)
                .filter(station -> station.isAiControlAllowed())
                .forEach(station -> {
                    station.setAiControlAllowed(false);
                    LOGGER.info("Stopped DJ control for station: {}", brand);
                });
    }

    private void sendDjWarning(String brand) {
        radioStationPool.getOnlineStationsSnapshot()
                .stream()
                .filter(station -> station.getSlugName().equals(brand))
                .filter(station ->
                        station.getStatus() == RadioStationStatus.ON_LINE ||
                                station.getStatus() == RadioStationStatus.WARMING_UP ||
                                station.getStatus() == RadioStationStatus.QUEUE_SATURATED ||
                                station.getStatus() == RadioStationStatus.IDLE)
                .forEach(station -> {
                    memoryService.resetMemory(brand, MemoryType.EVENT)
                            .subscribe().with(
                                    deletedCount -> {
                                        LOGGER.debug("Deleted {} existing events for station: {}", deletedCount, brand);
                                        createEventMemory(brand,  EventType.SHIFT_ENDING,"The shift of the dj ended");
                                    },
                                    failure -> LOGGER.error("Failed to delete existing events for station: {}", brand, failure)
                            );

                    LOGGER.info("Sent DJ shift ending warning for station: {}", brand);
                });
    }

    private void createEventMemory(String stationSlugName, EventType eventType, String message) {
        memoryService.addEvent(stationSlugName, eventType, null, message)
                .subscribe().with(
                        id -> LOGGER.debug("Memory created with ID: {} for station: {}", id, stationSlugName),
                        failure -> LOGGER.error("Failed to create memory for station: {}", stationSlugName, failure)
                );
    }
}
