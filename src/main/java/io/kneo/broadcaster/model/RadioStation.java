package io.kneo.broadcaster.model;

import io.kneo.broadcaster.dto.cnst.AiAgentStatus;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.model.cnst.ManagedBy;
import io.kneo.broadcaster.model.scheduler.Schedulable;
import io.kneo.broadcaster.model.scheduler.Scheduler;
import io.kneo.broadcaster.service.stream.IStreamManager;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.SecureDataEntity;
import io.kneo.officeframe.cnst.CountryCode;
import io.kneo.officeframe.model.Label;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Setter
@Getter
@NoArgsConstructor
public class RadioStation extends SecureDataEntity<UUID> implements Schedulable {
    private EnumMap<LanguageCode, String> localizedName = new EnumMap<>(LanguageCode.class);
    private IStreamManager playlist;
    private String slugName;
    private ZoneId timeZone;
    private Integer archived;
    private CountryCode country;
    private long bitRate;
    private ManagedBy managedBy = ManagedBy.ITSELF;
    private String color;
    private String description;
    private Scheduler scheduler;
    private UUID aiAgentId;
    private UUID profileId;
    private List<Label> labelList;
    //*transient**//
    @Deprecated //???
    private RadioStationStatus status;
    private AiAgentStatus aiAgentStatus;
    private List<StatusChangeRecord> statusHistory = new LinkedList<>();
    private boolean AiControlAllowed;

    public void setStatus(RadioStationStatus newStatus) {
        if (this.status != newStatus) {
            StatusChangeRecord record = new StatusChangeRecord(
                    LocalDateTime.now(),
                    this.status,
                    newStatus
            );
            statusHistory.add(record);
            this.status = newStatus;
        }
    }

    public long getCurrentAliveDurationMinutes() {
        if (statusHistory.isEmpty()) {
            return 0;
        }

        Optional<StatusChangeRecord> lastOnlineTransition = statusHistory.stream()
                .filter(record -> isAliveStatus(record.getNewStatus()) &&
                        !isAliveStatus(record.getOldStatus()))
                .reduce((first, second) -> second);

        if (lastOnlineTransition.isEmpty()) {
            return 0;
        }

        LocalDateTime onlineSince = lastOnlineTransition.get().getTimestamp();

        if (isAliveStatus(status)) {
            return Duration.between(onlineSince, LocalDateTime.now()).toMinutes();
        } else {
            Optional<StatusChangeRecord> offlineTransition = statusHistory.stream()
                    .filter(record -> record.getTimestamp().isAfter(onlineSince) &&
                            !isAliveStatus(record.getNewStatus()) &&
                            isAliveStatus(record.getOldStatus()))
                    .findFirst();

            return offlineTransition.map(statusChangeRecord -> Duration.between(onlineSince, statusChangeRecord.getTimestamp()).toMinutes()).orElse(0L);
        }
    }

    public String toString() {
        return String.format("id: %s, slug: %s", getId(), slugName);
    }


    private boolean isAliveStatus(RadioStationStatus status) {
        return status == RadioStationStatus.ON_LINE;
    }

    @Getter
    public static class StatusChangeRecord {
        private final LocalDateTime timestamp;
        private final RadioStationStatus oldStatus;
        private final RadioStationStatus newStatus;

        public StatusChangeRecord(LocalDateTime timestamp,
                                  RadioStationStatus oldStatus,
                                  RadioStationStatus newStatus) {
            this.timestamp = timestamp;
            this.oldStatus = oldStatus;
            this.newStatus = newStatus;
        }
    }

}