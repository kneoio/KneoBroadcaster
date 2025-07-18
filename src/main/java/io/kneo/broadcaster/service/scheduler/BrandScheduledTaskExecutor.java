package io.kneo.broadcaster.service.scheduler;

import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.model.cnst.MemoryType;
import io.kneo.broadcaster.service.MemoryService;
import io.kneo.broadcaster.service.stream.RadioStationPool;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalTime;

@ApplicationScoped
public class BrandScheduledTaskExecutor extends AbstractTaskExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger(BrandScheduledTaskExecutor.class);

    @Inject
    private RadioStationPool radioStationPool;

    @Inject
    private MemoryService memoryService;

    @Override
    protected Uni<Void> executeTask(ScheduleExecutionContext context) {
        if (!(context.getEntity() instanceof RadioStation radioStation)) {
            return Uni.createFrom().voidItem();
        }

        CronTaskType taskType = context.getTask().getType();
        String target = context.getTask().getTarget();
        String taskKey = generateTaskKey(radioStation, taskType, target);

        LOGGER.debug("Checking task execution for: {} (key: {})", taskType, taskKey);

        return switch (taskType) {
            case PROCESS_DJ_CONTROL -> handleDjTimeWindow(radioStation, target, taskKey, context);
            default -> {
                LOGGER.warn("Unknown task type: {}", taskType);
                yield Uni.createFrom().voidItem();
            }
        };
    }

    @Override
    public boolean supports(CronTaskType taskType) {
        return CronTaskType.PROCESS_DJ_CONTROL == taskType;
    }

    private static final int DJ_SHIFT_WARNING_MINUTES = 7;

    private Uni<Void> handleDjTimeWindow(RadioStation station, String target, String taskKey, ScheduleExecutionContext context) {
        TaskState currentState = getRunningTask(taskKey);

        if (currentState != null && !isWithinTimeWindow(context)) {
            LOGGER.warn("Force stopping DJ control for station {} - outside time window", station.getSlugName());
            removeRunningTask(taskKey);
            return stopDjControl(station);
        }

        if (isAtWindowStart(context) && currentState == null && isWithinTimeWindow(context)) {
            LOGGER.info("Starting DJ control for station: {} with target: {}", station.getSlugName(), target);
            return startDjControl(station, target)
                    .onItem().invoke(() -> {
                        addRunningTask(taskKey, station.getId(), "run_dj", target);

                        String endTime = context.getTask().getTimeWindowTrigger().getEndTime();
                        LOGGER.info("DJ control for station {} will stop at {}", station.getSlugName(), endTime);
                    });
        }

        if (isAtWarningTime(context) && currentState != null) {
            String warningTaskKey = taskKey + "_warning";
            TaskState warningState = getRunningTask(warningTaskKey);

            if (warningState == null) {
                LOGGER.info("Sending DJ shift ending warning for station: {}", station.getSlugName());
                memoryService.upsert(station.getSlugName(), MemoryType.EVENT, "The shift of the dj ended")
                        .subscribe().with(
                                id -> System.out.println("Memory created with ID: " + id),
                                failure -> System.err.println("Failed to create memory: " + failure)
                        );
                addRunningTask(warningTaskKey, station.getId(), "dj_warning", target);
            }
        }

        if (isAtWindowEnd(context) && currentState != null) {
            LOGGER.info("Stopping DJ control for station: {}", station.getSlugName());
            removeRunningTask(taskKey);
            removeRunningTask(taskKey + "_warning");
            return stopDjControl(station);
        }

        LOGGER.debug("DJ control continuing for station: {} (no action needed)", station.getSlugName());
        return Uni.createFrom().voidItem();
    }

    private boolean isAtWarningTime(ScheduleExecutionContext context) {
        if (context.getTask().getTimeWindowTrigger() == null) return false;

        String currentTime = context.getCurrentTime();
        String endTime = context.getTask().getTimeWindowTrigger().getEndTime();

        LocalTime current = LocalTime.parse(currentTime);
        LocalTime end = LocalTime.parse(endTime);
        LocalTime warningTime = end.minusMinutes(DJ_SHIFT_WARNING_MINUTES);

        return !current.isBefore(warningTime) && current.isBefore(end);
    }



    private boolean isAtWindowStart(ScheduleExecutionContext context) {
        if (context.getTask().getTimeWindowTrigger() == null) return false;

        String currentTime = context.getCurrentTime();
        String startTime = context.getTask().getTimeWindowTrigger().getStartTime();

        LocalTime current = LocalTime.parse(currentTime);
        LocalTime start = LocalTime.parse(startTime);

        return !current.isBefore(start);
    }

    private boolean isAtWindowEnd(ScheduleExecutionContext context) {
        if (context.getTask().getTimeWindowTrigger() == null) return false;

        String currentTime = context.getCurrentTime();
        String endTime = context.getTask().getTimeWindowTrigger().getEndTime();

        LocalTime current = LocalTime.parse(currentTime);
        LocalTime end = LocalTime.parse(endTime);

        return !current.isBefore(end);
    }

    private String generateTaskKey(RadioStation station, CronTaskType taskType, String target) {
        return String.format("%s_%s_%s", station.getSlugName(), taskType, target);
    }

    private Uni<Void> startDjControl(RadioStation station, String target) {
        return Uni.createFrom().voidItem()
                .onItem().invoke(() -> {
                    radioStationPool.getOnlineStationsSnapshot()
                            .stream()
                            .filter(rs -> rs.getSlugName().equals(station.getSlugName()))
                            .filter(rs ->
                                    rs.getStatus() == RadioStationStatus.ON_LINE ||
                                            rs.getStatus() == RadioStationStatus.WAITING_FOR_CURATOR ||
                                            rs.getStatus() == RadioStationStatus.WARMING_UP)
                            .forEach(rs -> {
                                rs.setAiControlAllowed(true);
                                memoryService.upsert(station.getSlugName(), MemoryType.EVENT, "The shift of the dj started")
                                        .subscribe().with(
                                                id -> System.out.println("Memory created with ID: " + id),
                                                failure -> System.err.println("Failed to create memory: " + failure)
                                        );
                                LOGGER.info("Set AiControlAllowed=true for station: {}", rs.getSlugName());
                            });
                });
    }

    private Uni<Void> stopDjControl(RadioStation station) {
        return Uni.createFrom().voidItem()
                .onItem().invoke(() -> {
                    radioStationPool.getOnlineStationsSnapshot()
                            .stream()
                            .filter(rs -> rs.getSlugName().equals(station.getSlugName()))
                            .filter(rs ->
                                    rs.getStatus() == RadioStationStatus.ON_LINE ||
                                            rs.getStatus() == RadioStationStatus.WARMING_UP ||
                                            rs.getStatus() == RadioStationStatus.WAITING_FOR_CURATOR ||
                                            rs.getStatus() == RadioStationStatus.IDLE)
                            .forEach(rs -> {
                                rs.setAiControlAllowed(false);
                                LOGGER.info("Set AiControlAllowed=false for station: {}", rs.getSlugName());
                            });
                });
    }
}