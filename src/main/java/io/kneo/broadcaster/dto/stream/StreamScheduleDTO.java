package io.kneo.broadcaster.dto.stream;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

@Setter
@Getter
public class StreamScheduleDTO {
    private LocalDateTime createdAt;
    private LocalDateTime estimatedEndTime;
    private int totalScenes;
    private int totalSongs;
    private List<SceneScheduleDTO> scenes;

    @Setter
    @Getter
    public static class SceneScheduleDTO {
        private String sceneId;
        private String sceneTitle;
        private LocalDateTime scheduledStartTime;
        private LocalDateTime scheduledEndTime;
        private int durationSeconds;
        private List<ScheduledSongDTO> songs;
    }

    @Setter
    @Getter
    public static class ScheduledSongDTO {
        private String id;
        private String songId;
        private String title;
        private String artist;
        private LocalDateTime scheduledStartTime;
        private int estimatedDurationSeconds;
        private boolean played;
    }
}
