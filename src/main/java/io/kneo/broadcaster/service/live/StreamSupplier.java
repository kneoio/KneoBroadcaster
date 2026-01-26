package io.kneo.broadcaster.service.live;

import io.kneo.broadcaster.model.ScenePrompt;
import io.kneo.broadcaster.model.aiagent.AiAgent;
import io.kneo.broadcaster.model.cnst.GeneratedContentStatus;
import io.kneo.broadcaster.model.cnst.LanguageTag;
import io.kneo.broadcaster.model.soundfragment.SoundFragment;
import io.kneo.broadcaster.model.stream.IStream;
import io.kneo.broadcaster.model.stream.LiveScene;
import io.kneo.broadcaster.model.stream.PendingSongEntry;
import io.kneo.broadcaster.service.live.generated.GeneratedNewsService;
import io.kneo.broadcaster.service.live.generated.GeneratedWeatherService;
import io.kneo.broadcaster.service.soundfragment.SoundFragmentService;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public abstract class StreamSupplier {
    private static final Logger LOGGER = LoggerFactory.getLogger(StreamSupplier.class);

    @Inject
    GeneratedNewsService generatedNewsService;

    @Inject
    GeneratedWeatherService generatedWeatherService;

    protected List<SoundFragment> pickSongsFromScheduled(
            List<PendingSongEntry> scheduledSongs,
            Set<UUID> excludeIds
    ) {
        List<PendingSongEntry> available = scheduledSongs.stream()
                .filter(e -> !excludeIds.contains(e.getSoundFragment().getId()))
                .toList();

        if (available.isEmpty()) {
            return List.of();
        }

        int take = available.size() >= 2 && new Random().nextDouble() < 0.7 ? 2 : 1;
        return available.stream()
                .limit(take)
                .map(PendingSongEntry::getSoundFragment)
                .toList();
    }

    protected Uni<List<SoundFragment>> generateContentForScene(
            LiveScene liveScene,
            UUID brandId,
            SoundFragmentService soundFragmentService,
            AiAgent agent,
            IStream stream,
            LanguageTag airLanguage
    ) {
        liveScene.setGeneratedContentStatus(GeneratedContentStatus.PROCESSING);
        List<ScenePrompt> contentPrompts = liveScene.getContentPrompts();
        UUID promptId = contentPrompts.getFirst().getPromptId();
        if (liveScene.getGeneratedFragmentId() != null) {
            LOGGER.info("Scene {} already has generated fragment {}, checking if valid",
                    liveScene.getSceneTitle(), liveScene.getGeneratedFragmentId());
            return soundFragmentService.getById(liveScene.getGeneratedFragmentId())
                    .flatMap(fragment -> {
                        if (fragment.getExpiresAt() != null && fragment.getExpiresAt().isBefore(LocalDateTime.now())) {
                            LOGGER.info("Fragment {} expired at {}, regenerating",
                                    liveScene.getGeneratedFragmentId(), fragment.getExpiresAt());
                            return generateContent(promptId, agent, stream, brandId, liveScene, airLanguage);
                        }
                        LOGGER.info("Reusing valid fragment {} for scene {}",
                                liveScene.getGeneratedFragmentId(), liveScene.getSceneTitle());
                        liveScene.setGeneratedContentStatus(GeneratedContentStatus.REUSING);
                        return Uni.createFrom().item(List.of(fragment));
                    })
                    .onFailure().recoverWithUni(error -> {
                        LOGGER.warn("Fragment {} not found, regenerating", liveScene.getGeneratedFragmentId());
                        return generateContent(promptId, agent, stream, brandId, liveScene, airLanguage);
                    });
        }
        // If no fragment ID, try to reuse from another scene with same prompt (today's news)
        LiveScene sourceScene = stream.getStreamAgenda().getLiveScenes().stream()
                .filter(scene -> !scene.getSceneId().equals(liveScene.getSceneId()))
                .filter(scene -> scene.getGeneratedFragmentId() != null)
                .filter(scene -> scene.getContentPrompts().getFirst().getPromptId().equals(promptId))
                .filter(scene -> scene.getGeneratedContentTimestamp() != null
                        && scene.getGeneratedContentTimestamp().toLocalDate().equals(LocalDate.now()))
                .findFirst()
                .orElse(null);

        if (sourceScene != null) {
            UUID existingFragmentId = sourceScene.getGeneratedFragmentId();

            return soundFragmentService.getById(existingFragmentId)
                    .flatMap(fragment -> {
                        if (fragment.getExpiresAt() != null && fragment.getExpiresAt().isBefore(LocalDateTime.now())) {
                            LOGGER.info("Fragment {} expired at {}, re-generating", existingFragmentId, fragment.getExpiresAt());
                            return generateContent(promptId, agent, stream, brandId, liveScene, airLanguage);
                        }

                        LOGGER.info("Reusing today's news fragment {} from scene '{}' for scene '{}'",
                                existingFragmentId, sourceScene.getSceneTitle(), liveScene.getSceneTitle());
                        liveScene.setGeneratedFragmentId(existingFragmentId);
                        liveScene.setGeneratedContentTimestamp(sourceScene.getGeneratedContentTimestamp());
                        liveScene.setGeneratedContentStatus(GeneratedContentStatus.REUSING);
                        return Uni.createFrom().item(List.of(fragment));
                    })
                    .onFailure().recoverWithUni(error -> {
                        LOGGER.warn("Fragment {} not found, re-generating", existingFragmentId);
                        return generateContent(promptId, agent, stream, brandId, liveScene, airLanguage);
                    });
        }

        return generateContent(promptId, agent, stream, brandId, liveScene, airLanguage);
    }

    private Uni<List<SoundFragment>> generateContent(
            UUID promptId,
            AiAgent agent,
            IStream stream,
            UUID brandId,
            LiveScene activeEntry,
            LanguageTag airLanguage
    ) {
        LOGGER.info("Generating new content for scene: {} prompt: {}", activeEntry.getSceneTitle(), promptId);
        activeEntry.setGeneratedContentStatus(GeneratedContentStatus.PENDING);

        return generatedNewsService.generateFragment(promptId, agent, stream, brandId, activeEntry, airLanguage)
                .map(List::of)
                .onFailure().recoverWithUni(error -> {
                    LOGGER.error("Failed to generate content for prompt {}", promptId, error);
                    activeEntry.setGeneratedContentStatus(GeneratedContentStatus.ERROR);
                    return Uni.createFrom().item(List.of());
                });
    }
}
