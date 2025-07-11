package io.kneo.broadcaster.service.scheduler;

import io.kneo.broadcaster.model.RadioStation;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class RadioStreamTaskExecutor implements TaskExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger(RadioStreamTaskExecutor.class);

    @Override
    public Uni<Void> execute(ScheduleExecutionContext context) {
        if (!(context.getEntity() instanceof RadioStation radioStation)) {
            return Uni.createFrom().voidItem();
        }

        String taskType = context.getTask().getType();
        String target = context.getTask().getTarget();

        LOGGER.info("Executing {} for radio station: {} with target: {}",
                taskType, radioStation.getSlugName(), target);

        return switch (taskType) {
            case "START_STREAM" -> startStream(radioStation, target);
            case "STOP_STREAM" -> stopStream(radioStation);
            default -> {
                LOGGER.warn("Unknown task type: {}", taskType);
                yield Uni.createFrom().voidItem();
            }
        };
    }

    @Override
    public boolean supports(String taskType) {
        return "START_STREAM".equals(taskType) || "STOP_STREAM".equals(taskType);
    }

    private Uni<Void> startStream(RadioStation station, String target) {
        // Implement stream start logic here
        LOGGER.info("Starting stream for station: {} with target: {}", station.getSlugName(), target);
        return Uni.createFrom().voidItem();
    }

    private Uni<Void> stopStream(RadioStation station) {
        // Implement stream stop logic here
        LOGGER.info("Stopping stream for station: {}", station.getSlugName());
        return Uni.createFrom().voidItem();
    }
}