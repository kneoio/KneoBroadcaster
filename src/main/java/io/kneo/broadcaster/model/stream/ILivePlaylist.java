package io.kneo.broadcaster.model.stream;

import io.kneo.broadcaster.dto.cnst.AiAgentStatus;
import io.kneo.broadcaster.model.brand.AiOverriding;
import io.kneo.broadcaster.model.brand.BrandScriptEntry;
import io.kneo.broadcaster.model.brand.ProfileOverriding;
import io.kneo.broadcaster.model.cnst.ManagedBy;
import io.kneo.broadcaster.model.cnst.StreamStatus;
import io.kneo.broadcaster.model.cnst.SubmissionPolicy;
import io.kneo.broadcaster.service.stream.IStreamManager;
import io.kneo.core.localization.LanguageCode;
import io.kneo.officeframe.cnst.CountryCode;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;

public interface ILivePlaylist {

    UUID getId();

    String getSlugName();

    EnumMap<LanguageCode, String> getLocalizedName();

    ZoneId getTimeZone();

    long getBitRate();

    ManagedBy getManagedBy();

    StreamStatus getStatus();

    void setStatus(StreamStatus status);

    IStreamManager getStreamManager();

    void setStreamManager(IStreamManager streamManager);

    AiAgentStatus getAiAgentStatus();

    List<StatusChangeRecord> getStatusHistory();

    void setAiAgentStatus(AiAgentStatus currentAiStatus);

    void setStatusHistory(List<StatusChangeRecord> currentHistory);

    CountryCode getCountry();

    UUID getAiAgentId();

    AiOverriding getAiOverriding();

    String getColor();

    String getDescription();

    SubmissionPolicy getSubmissionPolicy();

    SubmissionPolicy getMessagingPolicy();

    void setColor(String s);

    void setPopularityRate(double popularityRate);

    void setAiAgentId(UUID aiAgentId);

    void setProfileId(UUID uuid);

    void setAiOverriding(AiOverriding aiOverriding);

    void setCountry(CountryCode country);

    void setScripts(List<BrandScriptEntry> brandScriptEntries);

    LocalDateTime getStartTime();

    UUID getProfileId();

    ProfileOverriding getProfileOverriding();

    double getPopularityRate();

    void setLastAgentContactAt(long l);
}
