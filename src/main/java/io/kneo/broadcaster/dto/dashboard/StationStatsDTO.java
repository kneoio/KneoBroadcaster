package io.kneo.broadcaster.dto.dashboard;

import io.kneo.broadcaster.model.cnst.GeneratedContentStatus;
import io.kneo.broadcaster.model.cnst.ManagedBy;
import io.kneo.broadcaster.model.cnst.SceneStatus;
import io.kneo.broadcaster.model.cnst.StreamStatus;
import io.kneo.broadcaster.model.stats.PlaylistManagerStats;
import io.kneo.broadcaster.model.stream.StatusChangeRecord;
import io.kneo.broadcaster.service.stream.HLSSongStats;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

@Setter
public class StationStatsDTO {
    @Getter
    private String brandName;
    @Getter
    private String zoneId; // IANA zone, e.g., "Europe/Riga"
    @Getter
    private StreamStatus status;
    @Getter
    private ManagedBy managedBy;
    @Getter
    private PlaylistManagerStats playlistManagerStats;
    @Getter
    private boolean heartbeat;
    @Getter
    private HLSSongStats songStatistics;
    @Getter
    private long currentListeners;
    @Getter
    private List<CountryStatsDTO> listenersByCountry;
    @Getter
    private List<StatusChangeRecord> statusHistory = new LinkedList<>();
    @Getter
    private AiDjStatsDTO aiDjStats;
    @Getter
    private ScheduleDTO schedule;

    @Setter
    @Getter
    public static class ScheduleDTO {
        private LocalDateTime createdAt;
        private List<ScheduleEntryDTO> entries;
    }

    @Setter
    @Getter
    public static class ScheduleEntryDTO {
        private UUID sceneId;
        private String sceneTitle;
        private LocalTime startTime;
        private LocalTime endTime;
        private boolean active;
        private double dayPercentage;
        private String searchInfo;
        private int songsCount;
        private int fetchedSongsCount;
        private UUID generatedSoundFragmentId;
        private LocalTime actualStartTime;
        private LocalTime actualEndTime;
        private SceneStatus status;
        private Long timingOffsetSeconds;
        private UUID generatedFragmentId;
        private LocalDateTime generatedContentTimestamp;
        private GeneratedContentStatus generatedContentStatus;
        private List<SongEntryDTO> songs;
    }

    @Setter
    @Getter
    public static class SongEntryDTO {
        private UUID songId;
        private String title;
        private String artist;
        private LocalDateTime scheduledStartTime;
    }
}