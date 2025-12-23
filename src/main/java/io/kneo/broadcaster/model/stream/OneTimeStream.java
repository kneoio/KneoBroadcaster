package io.kneo.broadcaster.model.stream;

import io.kneo.broadcaster.dto.cnst.AiAgentStatus;
import io.kneo.broadcaster.model.Scene;
import io.kneo.broadcaster.model.Script;
import io.kneo.broadcaster.model.brand.Brand;
import io.kneo.broadcaster.model.brand.BrandScriptEntry;
import io.kneo.broadcaster.model.cnst.ManagedBy;
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
    private StreamDeliveryState deliveryState;
    private Object streamSupplier;

    public OneTimeStream(Brand masterBrand, Script script, Map<String, Object> userVariables) {
        this.masterBrand = masterBrand;
        this.id = UUID.randomUUID();
        this.script = script;
        this.userVariables = userVariables;
        this.createdAt = LocalDateTime.now();
        this.managedBy = ManagedBy.DJ;

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
        this.slugName = WebHelper.generateSlug(
                displayName + "-" + Integer.toHexString((int) (Math.random() * 0xFFFFFF))
        );
        this.scripts = List.of(new BrandScriptEntry(script.getId(), userVariables));
    }

    private String buildOneTimeDisplayName() {
        String base =
                script.getSlugName() != null && !script.getSlugName().trim().isEmpty()
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

        List<SceneScheduleEntry> scenes = streamSchedule.getSceneScheduleEntries();
        
        boolean anySceneStarted = scenes.stream()
                .anyMatch(scene -> scene.getActualStartTime() != null);
        
        LOGGER.info("Station '{}': Finding active scene. Total scenes: {}, Any started: {}", 
                slugName, scenes.size(), anySceneStarted);
        
        if (!anySceneStarted) {
            SceneScheduleEntry firstScene = scenes.isEmpty() ? null : scenes.get(0);
            if (firstScene != null) {
                LOGGER.info("Station '{}': No scenes started yet, returning first scene: '{}'", 
                        slugName, firstScene.getSceneTitle());
            }
            return firstScene;
        }

        for (int i = 0; i < scenes.size(); i++) {
            SceneScheduleEntry entry = scenes.get(i);
            
            LOGGER.info("Station '{}': Checking scene {}/{}: '{}' - actualStart: {}, actualEnd: {}", 
                    slugName, i + 1, scenes.size(), entry.getSceneTitle(), 
                    entry.getActualStartTime(), entry.getActualEndTime());
            
            if (entry.getActualStartTime() != null && entry.getActualEndTime() == null) {
                LOGGER.info("Station '{}': Returning currently active scene: '{}'", 
                        slugName, entry.getSceneTitle());
                return entry;
            }
            
            if (entry.getActualStartTime() == null) {
                LOGGER.info("Station '{}': Returning next unstarted scene: '{}'", 
                        slugName, entry.getSceneTitle());
                return entry;
            }
        }

        LOGGER.warn("Station '{}': All scenes completed, returning null", slugName);
        return null;
    }

    public boolean isCompleted() {
        if (streamSchedule == null) {
            return true;
        }
        boolean completed = streamSchedule.getSceneScheduleEntries().stream()
                .allMatch(e -> e.getActualStartTime() != null && e.getActualEndTime() != null);
        LOGGER.info("Station '{}': isCompleted check = {}", slugName, completed);
        return completed;
    }

    @Override
    public List<SoundFragment> getNextScheduledSongs(Scene scene, int count) {
        if (streamSchedule == null || scene == null) {
            return List.of();
        }

        SceneScheduleEntry entry = streamSchedule.getSceneScheduleEntries().stream()
                .filter(s -> s.getSceneId().equals(scene.getId()))
                .findFirst()
                .orElse(null);

        if (entry == null) {
            return List.of();
        }

        LocalDateTime now = LocalDateTime.now();

        if (deliveryState == null || !scene.getId().equals(deliveryState.getSceneId())) {
            deliveryState = new StreamDeliveryState();
            deliveryState.reset(entry);
        }

        if (deliveryState.isExpired(now)) {
            return List.of();
        }

        int remaining = entry.getSongs().size() - deliveryState.getDeliveredSongIndex();
        int take = Math.min(2, Math.min(count, remaining));

        if (take <= 0) {
            return List.of();
        }

        List<SoundFragment> result = entry.getSongs().subList(
                        deliveryState.getDeliveredSongIndex(),
                        deliveryState.getDeliveredSongIndex() + take
                ).stream()
                .map(ScheduledSongEntry::getSoundFragment)
                .toList();

        deliveryState.setDeliveredSongIndex(deliveryState.getDeliveredSongIndex() + take);
        deliveryState.setLastDeliveryAt(now);

        return result;
    }

    @Override
    public String toString() {
        return String.format(
                "OneTimeStream[id: %s, slug: %s, baseBrand: %s]",
                id,
                slugName,
                masterBrand.getSlugName()
        );
    }
}

