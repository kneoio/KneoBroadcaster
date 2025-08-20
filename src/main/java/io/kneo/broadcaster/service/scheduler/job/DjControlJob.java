package io.kneo.broadcaster.service.scheduler.job;

import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
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

    private void startDjControl(String stationSlugName, String target) {
        radioStationPool.getOnlineStationsSnapshot()
                .stream()
                .filter(station -> station.getSlugName().equals(stationSlugName))
                .filter(station -> !station.isAiControlAllowed())
                /*/.filter(station ->
                        station.getStatus() == RadioStationStatus.ON_LINE ||
                        station.getStatus() == RadioStationStatus.WAITING_FOR_CURATOR ||
                        station.getStatus() == RadioStationStatus.QUEUE_SATURATED ||
                        station.getStatus() == RadioStationStatus.WARMING_UP)*/
                .forEach(station -> {
                    station.setAiControlAllowed(true);
                    createMemoryEvent(stationSlugName, "The shift of the dj started");
                    LOGGER.info("Started DJ control for station: {} with target: {}", stationSlugName, target);
                });
    }

    private void stopDjControl(String stationSlugName) {
        radioStationPool.getOnlineStationsSnapshot()
                .stream()
                .filter(station -> station.getSlugName().equals(stationSlugName))
                .filter(station ->
                        station.getStatus() == RadioStationStatus.ON_LINE ||
                        station.getStatus() == RadioStationStatus.WARMING_UP ||
                        station.getStatus() == RadioStationStatus.WAITING_FOR_CURATOR ||
                        station.getStatus() == RadioStationStatus.QUEUE_SATURATED ||
                        station.getStatus() == RadioStationStatus.IDLE)
                .filter(station -> station.isAiControlAllowed())
                .forEach(station -> {
                    station.setAiControlAllowed(false);
                    LOGGER.info("Stopped DJ control for station: {}", stationSlugName);
                });
    }

    private void sendDjWarning(String stationSlugName) {
        radioStationPool.getOnlineStationsSnapshot()
                .stream()
                .filter(station -> station.getSlugName().equals(stationSlugName))
                .filter(station ->
                        station.getStatus() == RadioStationStatus.ON_LINE ||
                        station.getStatus() == RadioStationStatus.WARMING_UP ||
                        station.getStatus() == RadioStationStatus.WAITING_FOR_CURATOR ||
                        station.getStatus() == RadioStationStatus.QUEUE_SATURATED ||
                        station.getStatus() == RadioStationStatus.IDLE)
                .forEach(station -> {
                    createMemoryEvent(stationSlugName, "The shift of the dj ended");
                    LOGGER.info("Sent DJ shift ending warning for station: {}", stationSlugName);
                });
    }

    private void createMemoryEvent(String stationSlugName, String message) {
        memoryService.upsert(stationSlugName, MemoryType.EVENT, message)
                .subscribe().with(
                        id -> LOGGER.debug("Memory created with ID: {} for station: {}", id, stationSlugName),
                        failure -> LOGGER.error("Failed to create memory for station: {}", stationSlugName, failure)
                );
    }
}
