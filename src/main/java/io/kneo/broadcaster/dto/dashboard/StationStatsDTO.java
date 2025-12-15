package io.kneo.broadcaster.dto.dashboard;

import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.model.cnst.ManagedBy;
import io.kneo.broadcaster.model.stats.PlaylistManagerStats;
import io.kneo.broadcaster.model.stream.StatusChangeRecord;
import io.kneo.broadcaster.service.stream.HLSSongStats;
import lombok.Getter;
import lombok.Setter;

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
    private AiDjStats aiDjStats;
}