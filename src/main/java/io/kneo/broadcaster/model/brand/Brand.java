package io.kneo.broadcaster.model.brand;

import io.kneo.broadcaster.dto.cnst.AiAgentStatus;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.model.cnst.ManagedBy;
import io.kneo.broadcaster.model.cnst.SubmissionPolicy;
import io.kneo.broadcaster.service.stream.IStreamManager;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.SecureDataEntity;
import io.kneo.officeframe.cnst.CountryCode;
import io.kneo.officeframe.model.Label;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

@Setter
@Getter
@NoArgsConstructor
public class Brand extends SecureDataEntity<UUID> {

    private EnumMap<LanguageCode, String> localizedName = new EnumMap<>(LanguageCode.class);
    private IStreamManager streamManager;
    private String slugName;
    private ZoneId timeZone;
    private Integer archived;
    private Integer isTemporary = 0;
    private CountryCode country;
    private long bitRate;
    private ManagedBy managedBy = ManagedBy.ITSELF;
    private String color;
    private String description;
    private String titleFont;
    private double popularityRate;
    private UUID aiAgentId;
    private UUID profileId;
    private AiOverriding aiOverriding;
    private ProfileOverriding profileOverriding;
    private SubmissionPolicy oneTimeStreamPolicy = SubmissionPolicy.NOT_ALLOWED;
    private SubmissionPolicy submissionPolicy = SubmissionPolicy.NOT_ALLOWED;
    private SubmissionPolicy messagingPolicy = SubmissionPolicy.REVIEW_REQUIRED;
    private List<Label> labelList;
    private List<BrandScriptEntry> scripts;

    //*transient**//
    @Deprecated //???
    private RadioStationStatus status;
    private AiAgentStatus aiAgentStatus;
    private List<StatusChangeRecord> statusHistory = new LinkedList<>();
    private Long lastAgentContactAt;
    private LocalDateTime startTime;

    public void setStatus(RadioStationStatus newStatus) {
        if (this.status != newStatus) {
            StatusChangeRecord record = new StatusChangeRecord(
                    LocalDateTime.now(),
                    this.status,
                    newStatus
            );
            if (statusHistory.isEmpty()) {
                startTime = record.timestamp();
            }
            statusHistory.add(record);
            this.status = newStatus;
        }
    }

    public String toString() {
        return String.format("id: %s, slug: %s", getId(), slugName);
    }

    public record StatusChangeRecord(LocalDateTime timestamp, RadioStationStatus oldStatus,
                                     RadioStationStatus newStatus) {
    }
}