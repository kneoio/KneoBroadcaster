package io.kneo.broadcaster.model.stream;

import io.kneo.broadcaster.dto.cnst.AiAgentStatus;
import io.kneo.broadcaster.model.Script;
import io.kneo.broadcaster.model.brand.Brand;
import io.kneo.broadcaster.model.brand.BrandScriptEntry;
import io.kneo.broadcaster.model.cnst.ManagedBy;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Setter
@Getter
public class OneTimeStream extends AbstractStream {

    private static final Logger LOGGER = LoggerFactory.getLogger(OneTimeStream.class);

    private Script script;
    private Map<String, Object> userVariables;
    private AiAgentStatus aiAgentStatus;
    private StreamDeliveryState deliveryState;

    // per-stream mutable state (moved from supplier)
    private UUID currentSceneId;
    private final Map<UUID, Set<UUID>> fetchedSongsByScene = new HashMap<>();
    private LocalDateTime lastDeliveryAt;
    private int lastDeliveredSongsDuration;
    private LocalDateTime scheduledOfflineAt;

    public OneTimeStream(Brand masterBrand, Script script, Map<String, Object> userVariables) {
        this.masterBrand = masterBrand;
        this.id = UUID.randomUUID();
        this.script = script;
        this.userVariables = userVariables;
        this.createdAt = LocalDateTime.now();
        this.managedBy = ManagedBy.DJ;
        String displayName = buildDisplayName();
        EnumMap<LanguageCode, String> localizedName = new EnumMap<>(LanguageCode.class);
        this.slugName = WebHelper.generateSlug(
                displayName + "-" + Integer.toHexString((int) (Math.random() * 0xFFFFFF))
        );
        localizedName.put(LanguageCode.en, displayName);
        this.localizedName = localizedName;
        this.timeZone = masterBrand.getTimeZone();
        this.color = WebHelper.generateRandomBrightColor();
        this.aiAgentId = masterBrand.getAiAgentId();
        this.profileId = script.getDefaultProfileId();
        this.bitRate = masterBrand.getBitRate();
        this.aiOverriding = masterBrand.getAiOverriding();
        this.country = masterBrand.getCountry();
        this.scripts = List.of(new BrandScriptEntry(script.getId(), userVariables));
    }

    public SceneScheduleEntry findActiveSceneEntry() {
        List<SceneScheduleEntry> scenes = streamSchedule.getSceneScheduleEntries();

        boolean anySceneStarted = scenes.stream()
                .anyMatch(scene -> scene.getActualStartTime() != null);

        if (!anySceneStarted) {
            return scenes.isEmpty() ? null : scenes.getFirst();
        }

        for (SceneScheduleEntry entry : scenes) {
            if (entry.getActualStartTime() != null && entry.getActualEndTime() == null) {
                return entry;
            }
            if (entry.getActualStartTime() == null) {
                return entry;
            }
        }
        return null;
    }

    public boolean isCompleted() {
        return streamSchedule.getSceneScheduleEntries().stream()
                .allMatch(e -> e.getActualStartTime() != null && e.getActualEndTime() != null);
    }

    public Set<UUID> getFetchedSongsInScene(UUID sceneId) {
        return fetchedSongsByScene.computeIfAbsent(sceneId, k -> new HashSet<>());
    }

    public void clearSceneState(UUID sceneId) {
        fetchedSongsByScene.remove(sceneId);
    }

    private String buildDisplayName() {
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
}
