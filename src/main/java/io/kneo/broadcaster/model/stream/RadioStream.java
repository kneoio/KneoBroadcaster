package io.kneo.broadcaster.model.stream;

import io.kneo.broadcaster.dto.cnst.AiAgentStatus;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.model.brand.AiOverriding;
import io.kneo.broadcaster.model.brand.Brand;
import io.kneo.broadcaster.model.brand.BrandScriptEntry;
import io.kneo.broadcaster.model.brand.ProfileOverriding;
import io.kneo.broadcaster.model.cnst.ManagedBy;
import io.kneo.broadcaster.model.cnst.SubmissionPolicy;
import io.kneo.broadcaster.service.stream.IStreamManager;
import io.kneo.core.localization.LanguageCode;
import io.kneo.officeframe.cnst.CountryCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;

@Setter
@Getter
@NoArgsConstructor
public class RadioStream implements IStream {

    private UUID id;
    private String slugName;
    private EnumMap<LanguageCode, String> localizedName = new EnumMap<>(LanguageCode.class);
    private ZoneId timeZone;
    private long bitRate;
    private ManagedBy managedBy = ManagedBy.ITSELF;
    private RadioStationStatus status = RadioStationStatus.OFF_LINE;
    private IStreamManager streamManager;
    private UUID baseBrandId;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;

    public RadioStream(Brand baseBrand) {
        this.id = UUID.randomUUID();
        this.baseBrandId = baseBrand.getId();
        this.slugName = baseBrand.getSlugName() + "-rs-" + System.currentTimeMillis();
        this.localizedName = new EnumMap<>(baseBrand.getLocalizedName());
        this.timeZone = baseBrand.getTimeZone();
        this.bitRate = baseBrand.getBitRate();
        this.managedBy = baseBrand.getManagedBy();
        this.createdAt = LocalDateTime.now();
    }

    @Override
    public String toString() {
        return String.format("OneTimeStream[id: %s, slug: %s, baseBrand: %s]", id, slugName, baseBrandId);
    }

    @Override
    public AiAgentStatus getAiAgentStatus() {
        return null;
    }

    @Override
    public List<Brand.StatusChangeRecord> getStatusHistory() {
        return List.of();
    }

    @Override
    public void setAiAgentStatus(AiAgentStatus currentAiStatus) {

    }

    @Override
    public void setStatusHistory(List<Brand.StatusChangeRecord> currentHistory) {

    }

    @Override
    public CountryCode getCountry() {
        return null;
    }

    @Override
    public UUID getAiAgentId() {
        return null;
    }

    @Override
    public AiOverriding getAiOverriding() {
        return null;
    }

    @Override
    public String getColor() {
        return "";
    }

    @Override
    public String getDescription() {
        return "";
    }

    @Override
    public SubmissionPolicy getSubmissionPolicy() {
        return null;
    }

    @Override
    public SubmissionPolicy getMessagingPolicy() {
        return null;
    }

    @Override
    public void setColor(String s) {

    }

    @Override
    public void setPopularityRate(double i) {

    }

    @Override
    public void setAiAgentId(UUID aiAgentId) {

    }

    @Override
    public void setProfileId(UUID uuid) {

    }

    @Override
    public void setAiOverriding(AiOverriding aiOverriding) {

    }

    @Override
    public void setCountry(CountryCode country) {

    }

    @Override
    public void setScripts(List<BrandScriptEntry> brandScriptEntries) {

    }

    @Override
    public LocalDateTime getStartTime() {
        return null;
    }

    @Override
    public UUID getProfileId() {
        return null;
    }

    @Override
    public ProfileOverriding getProfileOverriding() {
        return null;
    }

    @Override
    public double getPopularityRate() {
        return 0;
    }

    @Override
    public void setLastAgentContactAt(long l) {

    }
}
