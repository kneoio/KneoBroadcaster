package io.kneo.broadcaster.model.stream;

import io.kneo.broadcaster.dto.cnst.AiAgentStatus;
import io.kneo.broadcaster.model.Scene;
import io.kneo.broadcaster.model.brand.Brand;
import io.kneo.broadcaster.model.cnst.SubmissionPolicy;
import io.kneo.broadcaster.model.soundfragment.SoundFragment;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;

@Setter
@Getter
@NoArgsConstructor
public class RadioStream extends AbstractStream {
    private static final Logger LOGGER = LoggerFactory.getLogger(RadioStream.class);

    public RadioStream(Brand brand) {
        this.masterBrand = brand;
        this.id = brand.getId();
        this.slugName = brand.getSlugName();
        this.localizedName = new EnumMap<>(brand.getLocalizedName());
        this.timeZone = brand.getTimeZone();
        this.bitRate = brand.getBitRate();
        this.managedBy = brand.getManagedBy();
        this.createdAt = LocalDateTime.now();
        this.popularityRate = brand.getPopularityRate();
        this.timeZone = brand.getTimeZone();
        this.color = brand.getColor();
        this.aiAgentId = brand.getAiAgentId();
        this.profileId = brand.getProfileId();
        this.bitRate = brand.getBitRate();
        this.aiOverriding = brand.getAiOverriding();
        this.profileOverriding = brand.getProfileOverriding();
        this.country = brand.getCountry();
        this.scripts = brand.getScripts();
    }

    @Override
    public String toString() {
        return String.format("RadioStream[id: %s, slug: %s, baseBrand: %s]", id, slugName, masterBrand.getSlugName());
    }

    @Override
    public AiAgentStatus getAiAgentStatus() {
        return null;
    }

    @Override
    public void setAiAgentStatus(AiAgentStatus currentAiStatus) {

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
    public void setLastAgentContactAt(long l) {

    }

    @Override
    public SceneScheduleEntry findActiveSceneEntry() {
        if (streamSchedule == null) {
            LOGGER.warn("Station '{}': No stream schedule available", slugName);
            return null;
        }

        LocalDateTime now = LocalDateTime.now();
        for (SceneScheduleEntry entry : streamSchedule.getSceneSchedules()) {
            if (!now.isBefore(entry.getScheduledStartTime()) && now.isBefore(entry.getScheduledEndTime())) {
                LOGGER.debug("Station '{}': Scene '{}' is active (now: {}, scene range: {} - {})",
                        slugName, entry.getSceneTitle(), now, entry.getScheduledStartTime(), entry.getScheduledEndTime());
                return entry;
            }
        }

        LOGGER.info("Station '{}': No active scene found at {}. Schedule may need refresh.", slugName, now);
        return null;
    }

    @Override
    public List<SoundFragment> getNextScheduledSongs(Scene scene, int count) {
        if (streamSchedule == null || scene == null) {
            LOGGER.warn("Station '{}': No stream schedule or scene provided", slugName);
            return List.of();
        }

        SceneScheduleEntry sceneEntry = streamSchedule.getSceneSchedules().stream()
                .filter(s -> s.getSceneId().equals(scene.getId()))
                .findFirst()
                .orElse(null);

        if (sceneEntry == null) {
            LOGGER.warn("Station '{}': Scene '{}' not found in schedule", slugName, scene.getTitle());
            return List.of();
        }

        List<SoundFragment> songs = sceneEntry.getSongs().stream()
                .filter(s -> !s.isPlayed())
                .limit(count)
                .peek(ScheduledSongEntry::markAsPlayed)
                .map(ScheduledSongEntry::getSoundFragment)
                .toList();

        LOGGER.debug("Station '{}': Retrieved {} scheduled songs for scene '{}'",
                slugName, songs.size(), scene.getTitle());
        return songs;
    }
}
