package io.kneo.broadcaster.service.stream;

import io.kneo.broadcaster.model.ScenePrompt;
import io.kneo.broadcaster.model.cnst.GeneratedContentStatus;
import io.kneo.broadcaster.model.cnst.LanguageTag;
import io.kneo.broadcaster.model.cnst.WayOfSourcing;
import io.kneo.broadcaster.model.soundfragment.SoundFragment;
import io.kneo.broadcaster.model.stream.IStream;
import io.kneo.broadcaster.model.stream.LiveScene;
import io.kneo.broadcaster.model.stream.StreamAgenda;
import io.kneo.broadcaster.service.AiAgentService;
import io.kneo.broadcaster.service.live.generated.GeneratedNewsService;
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

/*
* To trigger news generation for a scene
* from UI
* */

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

        List<LiveScene> matchingScenes = schedule.getLiveScenes().stream()
                .filter(s -> s.getSceneId().equals(sceneId))
                .toList();
        
        if (matchingScenes.isEmpty()) {
            return Uni.createFrom().failure(new IllegalArgumentException("Scene not found in schedule: " + sceneId));
        }
        
        if (matchingScenes.size() > 1) {
            LOGGER.warn("Found {} scenes with same ID {}: This should not happen!", matchingScenes.size(), sceneId);
        }
        
        LiveScene liveScene = matchingScenes.getFirst();

        if (liveScene.getSourcing() != WayOfSourcing.GENERATED) {
            return Uni.createFrom().failure(new IllegalArgumentException(
                    "Scene is not GENERATED type: " + liveScene.getSceneTitle() + " (sourcing: " + liveScene.getSourcing() + ")"
            ));
        }

        List<ScenePrompt> prompts = liveScene.getContentPrompts();
        if (prompts == null || prompts.isEmpty()) {
            return Uni.createFrom().failure(new IllegalArgumentException(
                    "Scene has no prompts configured: " + liveScene.getSceneTitle()
            ));
        }

        LOGGER.info("Before clearing - Scene '{}' has {} songs", liveScene.getSceneTitle(), liveScene.getSongs().size());
        if (!liveScene.getSongs().isEmpty()) {
            LOGGER.info("Existing songs: {}", liveScene.getSongs().stream()
                    .map(s -> s.getSoundFragment().getTitle())
                    .toList());
        }
        
        liveScene.getSongs().clear();
        liveScene.setGeneratedFragmentId(null);
        liveScene.setGeneratedContentTimestamp(null);
        liveScene.setGeneratedContentStatus(null);
        
        LOGGER.info("After clearing - Scene '{}' now has {} songs", liveScene.getSceneTitle(), liveScene.getSongs().size());

        UUID promptId = prompts.getFirst().getPromptId();
        UUID brandId = stream.getMasterBrand().getId();

        LOGGER.info("Triggering content generation for scene '{}' ({}), prompt: {}",
                liveScene.getSceneTitle(), sceneId, promptId);

        return aiAgentService.getById(stream.getAiAgentId(), SuperUser.build(), LanguageCode.en)
                .chain(agent ->
                {
                    LanguageTag broadcastingLanguage = AiHelperUtils.selectLanguageByWeight(agent);
                    return generatedNewsService.generateFragment(promptId, agent, stream, brandId, liveScene, broadcastingLanguage);
                })
                .onFailure().recoverWithUni(error -> {
                    LOGGER.error("Failed to generate content for scene '{}' ({}), prompt: {}", 
                            liveScene.getSceneTitle(), sceneId, promptId, error);
                    liveScene.setGeneratedContentStatus(GeneratedContentStatus.ERROR);
                    return Uni.createFrom().failure(error);
                });
    }
}
