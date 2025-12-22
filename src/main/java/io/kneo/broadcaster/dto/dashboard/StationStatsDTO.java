package io.kneo.broadcaster.dto.dashboard;

import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.model.cnst.ManagedBy;
import io.kneo.broadcaster.model.cnst.SceneStatus;
import io.kneo.broadcaster.model.stats.PlaylistManagerStats;
import io.kneo.broadcaster.model.stream.StatusChangeRecord;
import io.kneo.broadcaster.service.stream.HLSSongStats;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalTime;
import java.util.LinkedList;
import java.util.List;

@Setter
public class StationStatsDTO {
    @Getter
    private String brandName;
    @Getter
    private String zoneId; // IANA zone, e.g., "Europe/Riga"
    @Getter
    private RadioStationStatus status;
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
    private List<ScheduleEntryDTO> schedule;

    @Setter
    @Getter
    public static class ScheduleEntryDTO {
        private String sceneTitle;
        private LocalTime startTime;
        private LocalTime endTime;
        private boolean active;
        private String sourcing;
        private String playlistTitle;
        private String artist;
        private String searchTerm;
        private int songsCount;
        private int fetchedSongsCount;

        private java.time.LocalDateTime actualStartTime;
        private java.time.LocalDateTime actualEndTime;
        private SceneStatus status;
        private Long timingOffsetSeconds;
    }
}