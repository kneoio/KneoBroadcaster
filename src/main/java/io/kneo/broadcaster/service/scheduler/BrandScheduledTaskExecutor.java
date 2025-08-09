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

@ApplicationScoped
public class BrandScheduledTaskExecutor extends AbstractTaskExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger(BrandScheduledTaskExecutor.class);
    private static final int DJ_SHIFT_WARNING_MINUTES = 7;

    @Inject
    private RadioStationPool radioStationPool;

    @Inject
    private MemoryService memoryService;

    @Override
    protected Uni<Void> executeTask(ScheduleExecutionContext context) {
        if (!(context.entity() instanceof RadioStation radioStation)) {
            return Uni.createFrom().voidItem();
        } else if (radioStation.getStatus() == null || radioStation.getStatus() == RadioStationStatus.OFF_LINE) {
            return Uni.createFrom().voidItem();
        }
        CronTaskType taskType = context.task().getType();
        String target = context.task().getTarget();
        String taskKey = TaskKeyGenerator.generate(radioStation.getSlugName(), taskType.toString(), target);

        LOGGER.info("Checking task execution for: {} (key: {})", taskType, taskKey);

        if (taskType == CronTaskType.PROCESS_DJ_CONTROL) {
            return handleDjTimeWindow(radioStation, target, taskKey, context);
        } else {
            LOGGER.warn("Unknown task type: {}", taskType);
            return Uni.createFrom().voidItem();
        }
    }

    @Override
    public boolean supports(CronTaskType taskType) {
        return CronTaskType.PROCESS_DJ_CONTROL == taskType;
    }

    private Uni<Void> handleDjTimeWindow(RadioStation station, String target, String taskKey, ScheduleExecutionContext context) {
        TaskState currentState = getRunningTask(taskKey);

        // Log initial state
        LOGGER.info("=== DJ Time Window Check for station: {} ===", station.getSlugName());
        //LOGGER.info("Task key: {}", taskKey);
        //LOGGER.info("Target: {}", target);
        //LOGGER.info("Current state exists: {}", currentState != null);
        //LOGGER.info("Current time: {}", context.getCurrentTime());

        if (context.task().getTimeWindowTrigger() != null) {
            LOGGER.info("Start time: {}", context.task().getTimeWindowTrigger().getStartTime());
            LOGGER.info("End time: {}", context.task().getTimeWindowTrigger().getEndTime());
        } else {
            LOGGER.warn("TimeWindowTrigger is null");
        }

        // Check window conditions
        boolean withinWindow = isWithinTimeWindow(context);
        boolean atStart = isAtWindowStart(context);
        boolean atEnd = isAtWindowEnd(context);
        boolean atWarning = isAtWarningTime(context);

        LOGGER.info("Window conditions - within: {}, atStart: {}, atEnd: {}, atWarning: {}",
                withinWindow, atStart, atEnd, atWarning);

        if (currentState != null && !withinWindow) {
            LOGGER.warn("Force stopping DJ control for station {} - outside time window (schedule changed)", station.getSlugName());
            removeRunningTask(taskKey);
            removeRunningTask(TaskKeyGenerator.generateWarning(taskKey));
            return stopDjControl(station);
        }

        if (atStart && currentState == null && withinWindow) {
            LOGGER.info("Starting DJ control for station: {} with target: {}", station.getSlugName(), target);
            return startDjControl(station, target)
                    .onItem().invoke(() -> {
                        addRunningTask(taskKey, station.getId(), "run_dj", target, station.getSlugName());
                        String endTime = context.task().getTimeWindowTrigger().getEndTime();
                        LOGGER.info("DJ control for station {} will stop at {}", station.getSlugName(), endTime);
                    });
        } else if (atStart) {
            LOGGER.info("At start time but not starting - currentState exists: {}, withinWindow: {}",
                    currentState != null, withinWindow);
        }

        // Handle missing state within window (server reboot or schedule change)
        if (withinWindow && currentState == null && !atStart && !atEnd) {
            LOGGER.warn("DJ control should be running but isn't (server reboot or schedule change) - starting now for station: {}", station.getSlugName());
            return startDjControl(station, target)
                    .onItem().invoke(() -> {
                        addRunningTask(taskKey, station.getId(), "run_dj", target, station.getSlugName());
                        String endTime = context.task().getTimeWindowTrigger().getEndTime();
                        LOGGER.info("DJ control for station {} will stop at {} (recovery start)", station.getSlugName(), endTime);
                    });
        }

        // Handle AI control recovery when task exists but AI control is disabled
        if (withinWindow && currentState != null && !atStart && !atEnd) {
            boolean needsRecovery = radioStationPool.getOnlineStationsSnapshot()
                    .stream()
                    .filter(rs -> rs.getSlugName().equals(station.getSlugName()))
                    .anyMatch(rs -> !rs.isAiControlAllowed());

            if (needsRecovery) {
                LOGGER.warn("Task exists but AI control disabled - recovering for station: {}", station.getSlugName());
                return startDjControl(station, target);
            }
        }

        if (atWarning && currentState != null) {
            String warningTaskKey = TaskKeyGenerator.generateWarning(taskKey);
            TaskState warningState = getRunningTask(warningTaskKey);

            if (warningState == null) {
                LOGGER.info("Sending DJ shift ending warning for station: {}", station.getSlugName());
                createMemoryEvent(station.getSlugName(), "The shift of the dj ended");
                addRunningTask(warningTaskKey, station.getId(), "dj_warning", target, station.getSlugName());
            } else {
                LOGGER.debug("Warning already sent for station: {}", station.getSlugName());
            }
        } else if (atWarning) {
            LOGGER.debug("At warning time but no current state for station: {}", station.getSlugName());
        }

        // Clean up warning task if no longer at warning time but warning exists
        if (!atWarning && currentState != null) {
            String warningTaskKey = TaskKeyGenerator.generateWarning(taskKey);
            TaskState warningState = getRunningTask(warningTaskKey);
            if (warningState != null) {
                LOGGER.info("Removing stale warning task for station: {} (no longer at warning time)", station.getSlugName());
                removeRunningTask(warningTaskKey);
            }
        }

        if (atEnd && currentState != null) {
            LOGGER.info("Stopping DJ control for station: {}", station.getSlugName());
            removeRunningTask(taskKey);
            removeRunningTask(TaskKeyGenerator.generateWarning(taskKey));
            return stopDjControl(station);
        } else if (atEnd) {
            LOGGER.info("At end time but no current state to stop for station: {}", station.getSlugName());
        }

        LOGGER.debug("DJ control continuing for station: {} (no action needed)", station.getSlugName());
        return Uni.createFrom().voidItem();
    }


    private boolean isAtWarningTime(ScheduleExecutionContext context) {
        if (context.task().getTimeWindowTrigger() == null) {
            return false;
        }

        String currentTime = context.currentTime();
        String endTime = context.task().getTimeWindowTrigger().getEndTime();

        return TimeUtils.isWarningTime(currentTime, endTime, DJ_SHIFT_WARNING_MINUTES);
    }

    private boolean isAtWindowStart(ScheduleExecutionContext context) {
        if (context.task().getTimeWindowTrigger() == null) {
            return false;
        }

        String currentTime = context.currentTime();
        String startTime = context.task().getTimeWindowTrigger().getStartTime();

        return TimeUtils.isAtTime(currentTime, startTime);
    }

    private boolean isAtWindowEnd(ScheduleExecutionContext context) {
        if (context.task().getTimeWindowTrigger() == null) {
            return false;
        }

        String currentTime = context.currentTime();
        String endTime = context.task().getTimeWindowTrigger().getEndTime();

        return TimeUtils.isAtTime(currentTime, endTime);
    }

    private Uni<Void> startDjControl(RadioStation station, String target) {
        return Uni.createFrom().voidItem()
                .onItem().invoke(() -> {
                    radioStationPool.getOnlineStationsSnapshot()
                            .stream()
                            .filter(brand -> brand.getSlugName().equals(station.getSlugName()))
                            .filter(brand ->
                                    brand.getStatus() == RadioStationStatus.ON_LINE ||
                                            brand.getStatus() == RadioStationStatus.WAITING_FOR_CURATOR ||
                                            brand.getStatus() == RadioStationStatus.QUEUE_SATURATED ||
                                            brand.getStatus() == RadioStationStatus.WARMING_UP)
                            .forEach(brand -> {
                                brand.setAiControlAllowed(true);
                                createMemoryEvent(station.getSlugName(), "The shift of the dj started");
                                LOGGER.info("Set AiControlAllowed=true for station: {}", brand.getSlugName());
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
                                            rs.getStatus() == RadioStationStatus.QUEUE_SATURATED ||
                                            rs.getStatus() == RadioStationStatus.IDLE)
                            .forEach(rs -> {
                                rs.setAiControlAllowed(false);
                                LOGGER.info("Set AiControlAllowed=false for station: {}", rs.getSlugName());
                            });
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