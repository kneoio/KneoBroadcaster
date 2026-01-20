package io.kneo.broadcaster.service.stream;

import io.kneo.broadcaster.model.stream.IStream;
import io.kneo.broadcaster.model.stream.StreamSchedule;
import io.kneo.core.model.user.SuperUser;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class ScheduleRebuildService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ScheduleRebuildService.class);

    private final RadioStationPool radioStationPool;
    private final StreamScheduleService streamScheduleService;

    @Inject
    public ScheduleRebuildService(
            RadioStationPool radioStationPool,
            StreamScheduleService streamScheduleService
    ) {
        this.radioStationPool = radioStationPool;
        this.streamScheduleService = streamScheduleService;
    }

    public Uni<StreamSchedule> rebuildSchedule(String brand) {
        IStream stream = radioStationPool.getStation(brand);
        if (stream == null) {
            return Uni.createFrom().failure(new IllegalArgumentException("Stream not found in pool: " + brand));
        }

        return streamScheduleService.buildLoopedStreamSchedule(
                stream.getMasterBrand().getId(),
                stream.getMasterBrand().getScripts().getFirst().getScriptId(),
                SuperUser.build()
        )
        .invoke(schedule -> {
            stream.setStreamSchedule(schedule);
            LOGGER.info("Schedule rebuilt for '{}': {} scenes, {} songs",
                    brand,
                    schedule != null ? schedule.getTotalScenes() : 0,
                    schedule != null ? schedule.getTotalSongs() : 0);
        });
    }
}
