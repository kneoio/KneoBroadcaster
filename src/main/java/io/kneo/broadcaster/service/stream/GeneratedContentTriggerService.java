package io.kneo.broadcaster.service.stream;

import io.kneo.broadcaster.model.ScenePrompt;
import io.kneo.broadcaster.model.cnst.LanguageTag;
import io.kneo.broadcaster.model.cnst.WayOfSourcing;
import io.kneo.broadcaster.model.soundfragment.SoundFragment;
import io.kneo.broadcaster.model.stream.IStream;
import io.kneo.broadcaster.model.stream.LiveScene;
import io.kneo.broadcaster.model.stream.StreamAgenda;
import io.kneo.broadcaster.service.AiAgentService;
import io.kneo.broadcaster.service.live.GeneratedNewsService;
import io.kneo.broadcaster.util.AiHelperUtils;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.SuperUser;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class GeneratedContentTriggerService {
    private static final Logger LOGGER = LoggerFactory.getLogger(GeneratedContentTriggerService.class);

    private final RadioStationPool radioStationPool;
    private final GeneratedNewsService generatedNewsService;
    private final AiAgentService aiAgentService;

    @Inject
    public GeneratedContentTriggerService(
            RadioStationPool radioStationPool,
            GeneratedNewsService generatedNewsService,
            AiAgentService aiAgentService
    ) {
        this.radioStationPool = radioStationPool;
        this.generatedNewsService = generatedNewsService;
        this.aiAgentService = aiAgentService;
    }

    public Uni<SoundFragment> triggerGeneration(String brand, UUID sceneId) {
        IStream stream = radioStationPool.getStation(brand);
        if (stream == null) {
            return Uni.createFrom().failure(new IllegalArgumentException("Stream not found in pool: " + brand));
        }

        StreamAgenda schedule = stream.getStreamAgenda();
        if (schedule == null) {
            return Uni.createFrom().failure(new IllegalStateException("Stream has no schedule: " + brand));
        }

        LiveScene scene = schedule.getLiveScenes().stream()
                .filter(s -> s.getSceneId().equals(sceneId))
                .findFirst()
                .orElse(null);

        if (scene == null) {
            return Uni.createFrom().failure(new IllegalArgumentException("Scene not found in schedule: " + sceneId));
        }

        if (scene.getSourcing() != WayOfSourcing.GENERATED) {
            return Uni.createFrom().failure(new IllegalArgumentException(
                    "Scene is not GENERATED type: " + scene.getSceneTitle() + " (sourcing: " + scene.getSourcing() + ")"
            ));
        }

        List<ScenePrompt> prompts = scene.getPrompts();
        if (prompts == null || prompts.isEmpty()) {
            return Uni.createFrom().failure(new IllegalArgumentException(
                    "Scene has no prompts configured: " + scene.getSceneTitle()
            ));
        }

        scene.getSongs().clear();
        scene.setGeneratedFragmentId(null);
        scene.setGeneratedContentTimestamp(null);

        UUID promptId = prompts.getFirst().getPromptId();
        UUID brandId = stream.getMasterBrand().getId();

        LOGGER.info("Triggering content generation for scene '{}' ({}), prompt: {}",
                scene.getSceneTitle(), sceneId, promptId);

        return aiAgentService.getById(stream.getAiAgentId(), SuperUser.build(), LanguageCode.en)
                .chain(agent ->
                {
                    LanguageTag broadcastingLanguage = AiHelperUtils.selectLanguageByWeight(agent);
                    return generatedNewsService.generateNewsFragment(promptId, agent, stream, brandId, scene, broadcastingLanguage);
                });
    }
}
