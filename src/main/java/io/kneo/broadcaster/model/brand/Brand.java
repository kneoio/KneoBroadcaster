package io.kneo.broadcaster.model.brand;

import com.semantyca.core.model.SecureDataEntity;
import com.semantyca.core.model.cnst.LanguageCode;
import com.semantyca.mixpla.model.cnst.StreamStatus;
import com.semantyca.officeframe.model.Label;
import com.semantyca.officeframe.model.cnst.CountryCode;
import io.kneo.broadcaster.dto.cnst.AiAgentStatus;
import io.kneo.broadcaster.model.cnst.ManagedBy;
import io.kneo.broadcaster.model.cnst.SubmissionPolicy;
import io.kneo.broadcaster.model.stream.StatusChangeRecord;
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
    //private IStreamManager streamManager;
    private String slugName;
    private ZoneId timeZone;
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
    private Owner owner;

    //*transient**//
    @Deprecated //???
    private StreamStatus status;
    private AiAgentStatus aiAgentStatus;
    private List<StatusChangeRecord> statusHistory = new LinkedList<>();
    private Long lastAgentContactAt;
    private LocalDateTime startTime;


    public void setStatus(StreamStatus newStatus) {
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

}