package io.kneo.broadcaster.model.stream;

import io.kneo.broadcaster.dto.cnst.AiAgentStatus;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.model.Scene;
import io.kneo.broadcaster.model.brand.AiOverriding;
import io.kneo.broadcaster.model.brand.Brand;
import io.kneo.broadcaster.model.brand.BrandScriptEntry;
import io.kneo.broadcaster.model.brand.ProfileOverriding;
import io.kneo.broadcaster.model.cnst.ManagedBy;
import io.kneo.broadcaster.model.soundfragment.SoundFragment;
import io.kneo.broadcaster.service.stream.IStreamManager;
import io.kneo.core.localization.LanguageCode;
import io.kneo.officeframe.cnst.CountryCode;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;

public interface IStream {

    Brand getMasterBrand();

    void setMasterBrand(Brand brand);

    UUID getId();

    String getSlugName();

    EnumMap<LanguageCode, String> getLocalizedName();

    ZoneId getTimeZone();

    long getBitRate();

    ManagedBy getManagedBy();

    RadioStationStatus getStatus();

    void setStatus(RadioStationStatus status);

    IStreamManager getStreamManager();

    AiAgentStatus getAiAgentStatus();

    List<StatusChangeRecord> getStatusHistory();

    void setAiAgentStatus(AiAgentStatus currentAiStatus);

    void setStatusHistory(List<StatusChangeRecord> currentHistory);

    CountryCode getCountry();

    UUID getAiAgentId();

    AiOverriding getAiOverriding();

    String getColor();

    default String getDescription() {
        return "";
    }

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

    long getLastAgentContactAt();

    StreamSchedule getStreamSchedule();

    void setStreamSchedule(StreamSchedule streamSchedule);

    SceneScheduleEntry findActiveSceneEntry();

    List<SoundFragment> getNextScheduledSongs(Scene scene, int count);
}
