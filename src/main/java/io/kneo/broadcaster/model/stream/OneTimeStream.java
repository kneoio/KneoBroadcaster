package io.kneo.broadcaster.model.stream;

import io.kneo.broadcaster.dto.cnst.AiAgentStatus;
import io.kneo.broadcaster.model.Scene;
import io.kneo.broadcaster.model.Script;
import io.kneo.broadcaster.model.brand.Brand;
import io.kneo.broadcaster.model.brand.BrandScriptEntry;
import io.kneo.broadcaster.model.brand.ProfileOverriding;
import io.kneo.broadcaster.model.cnst.SubmissionPolicy;
import io.kneo.broadcaster.model.soundfragment.SoundFragment;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.util.WebHelper;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Setter
@Getter
public class OneTimeStream extends AbstractStream {
    private static final Logger LOGGER = LoggerFactory.getLogger(OneTimeStream.class);
    private Script script;
    private Map<String, Object> userVariables;
    private AiAgentStatus aiAgentStatus;

    public OneTimeStream(Brand masterBrand, Script script, Map<String, Object> userVariables) {
        this.masterBrand = masterBrand;
        this.id = UUID.randomUUID();
        this.script = script;
        this.userVariables = userVariables;
        this.createdAt = LocalDateTime.now();
        String displayName = buildOneTimeDisplayName();
        EnumMap<LanguageCode, String> localizedName = new EnumMap<>(LanguageCode.class);
        localizedName.put(LanguageCode.en, displayName);
        this.localizedName = localizedName;
        this.timeZone = masterBrand.getTimeZone();
        this.color = WebHelper.generateRandomBrightColor();
        this.aiAgentId = masterBrand.getAiAgentId();
        this.profileId = script.getDefaultProfileId();
        this.bitRate = masterBrand.getBitRate();
        this.aiOverriding = masterBrand.getAiOverriding();
        this.country = masterBrand.getCountry();
        this.slugName = WebHelper.generateSlug(displayName + "-" + Integer.toHexString((int) (Math.random() * 0xFFFFFF)));
        this.scripts = List.of(new BrandScriptEntry(script.getId(), userVariables));
    }

    @Override
    public AiAgentStatus getAiAgentStatus() {
        return null;
    }

    @Override
    public String getDescription() {
        return "";
    }

    @Override
    public SubmissionPolicy getSubmissionPolicy() {
        return SubmissionPolicy.NOT_ALLOWED;
    }

    @Override
    public SubmissionPolicy getMessagingPolicy() {
        return SubmissionPolicy.NOT_ALLOWED;
    }

    @Override
    public void setAiAgentId(UUID aiAgentId) {

    }

    @Override
    public ProfileOverriding getProfileOverriding() {
        return null;
    }

    @Override
    public void setLastAgentContactAt(long l) {

    }


    @Override
    public String toString() {
        return String.format("OneTimeStream[id: %s, slug: %s, baseBrand: %s]", id, slugName, masterBrand.getSlugName());
    }


    private String buildOneTimeDisplayName() {
        String base = script.getSlugName() != null && !script.getSlugName().trim().isEmpty()
                ? script.getSlugName()
                : script.getName();

        List<String> parts = new ArrayList<>();
        parts.add(base);

        if (userVariables != null && !userVariables.isEmpty()) {
            userVariables.entrySet().stream()
                    .sorted(Comparator.comparing(Map.Entry::getKey))
                    .forEach(e -> {
                        Object v = e.getValue();
                        if (v != null) {
                            String s = v.toString().trim();
                            if (!s.isEmpty()) {
                                parts.add(s);
                            }
                        }
                    });
        }
        return String.join(" ", parts);
    }

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

        LOGGER.info("Station '{}': No active scene found at {}. Stream may have completed.", slugName, now);
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
