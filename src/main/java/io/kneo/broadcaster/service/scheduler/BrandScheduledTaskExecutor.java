package io.kneo.broadcaster.service.scheduler;

import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.model.cnst.MemoryType;
import io.kneo.broadcaster.service.MemoryService;
import io.kneo.broadcaster.service.stream.RadioStationPool;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class BrandScheduledTaskExecutor implements TaskExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger(BrandScheduledTaskExecutor.class);

    @Inject
    private RadioStationPool radioStationPool;

    @Inject
    private MemoryService memoryService;

    private final Map<String, TaskState> runningTasks = new ConcurrentHashMap<>();

    @Override
    public Uni<Void> execute(ScheduleExecutionContext context) {
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

    private Uni<Void> handleDjTimeWindow(RadioStation station, String target, String taskKey, ScheduleExecutionContext context) {
        TaskState currentState = runningTasks.get(taskKey);

        if (currentState != null && !isWithinTimeWindow(context)) {
            LOGGER.warn("Force stopping DJ control for station {} - outside time window", station.getSlugName());
            runningTasks.remove(taskKey);
            return stopDjControl(station);
        }

        if (isAtWindowStart(context) && currentState == null && isWithinTimeWindow(context)) {
            LOGGER.info("Starting DJ control for station: {} with target: {}", station.getSlugName(), target);
            return startDjControl(station, target)
                    .onItem().invoke(() -> {
                        runningTasks.put(taskKey, new TaskState(station.getId(), "run_dj", target, LocalDateTime.now()));

                        String endTime = context.getTask().getTimeWindowTrigger().getEndTime();
                        LOGGER.info("DJ control for station {} will stop at {}", station.getSlugName(), endTime);
                    });
        }

        if (isAtWindowEnd(context) && currentState != null) {
            LOGGER.info("Stopping DJ control for station: {}", station.getSlugName());
            runningTasks.remove(taskKey);
            return stopDjControl(station);
        }

        LOGGER.debug("DJ control continuing for station: {} (no action needed)", station.getSlugName());
        return Uni.createFrom().voidItem();
    }

    private String generateTaskKey(RadioStation station, CronTaskType taskType, String target) {
        return String.format("%s_%s_%s", station.getId(), taskType, target);
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
                                LOGGER.info("Set AiControlAllowed=true for station: {}", rs.getSlugName());
                                memoryService.upsert(station.getSlugName(), MemoryType.EVENT, "The shift of the dj started");
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
                                memoryService.upsert(station.getSlugName(), MemoryType.EVENT, "The shift of the dj ended");
                                LOGGER.info("Set AiControlAllowed=false for station: {}", rs.getSlugName());
                            });
                });
    }

    @Getter
    public static class TaskState {
        private final UUID entityId;
        private final String taskType;
        private final String target;
        private final LocalDateTime startTime;

        public TaskState(UUID entityId, String taskType, String target, LocalDateTime startTime) {
            this.entityId = entityId;
            this.taskType = taskType;
            this.target = target;
            this.startTime = startTime;
        }
    }
}