package io.kneo.broadcaster.model.stream;

import io.kneo.broadcaster.dto.cnst.AiAgentStatus;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.model.brand.AiOverriding;
import io.kneo.broadcaster.model.brand.Brand;
import io.kneo.broadcaster.model.brand.BrandScriptEntry;
import io.kneo.broadcaster.model.brand.ProfileOverriding;
import io.kneo.broadcaster.model.cnst.ManagedBy;
import io.kneo.broadcaster.service.stream.IStreamManager;
import io.kneo.core.localization.LanguageCode;
import io.kneo.officeframe.cnst.CountryCode;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

@Setter
@Getter
public abstract class AbstractStream implements IStream {
    protected UUID id;
    protected Brand masterBrand;
    protected String slugName;
    protected EnumMap<LanguageCode, String> localizedName = new EnumMap<>(LanguageCode.class);
    protected RadioStationStatus status = RadioStationStatus.OFF_LINE;
    protected List<StatusChangeRecord> statusHistory = new LinkedList<>();
    protected LocalDateTime startTime;
    protected IStreamManager streamManager;
    protected ZoneId timeZone;
    protected long bitRate;
    protected ManagedBy managedBy = ManagedBy.ITSELF;
    protected String color;
    protected CountryCode country;
    protected double popularityRate = 5;
    protected UUID aiAgentId;
    protected UUID profileId;
    protected AiOverriding aiOverriding;
    protected List<BrandScriptEntry> scripts;
    protected ProfileOverriding profileOverriding;
    protected LocalDateTime createdAt;
    protected LocalDateTime expiresAt;
    protected StreamSchedule streamSchedule;
    protected AiAgentStatus aiAgentStatus;
    protected long lastAgentContactAt;

    @Override
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
}
