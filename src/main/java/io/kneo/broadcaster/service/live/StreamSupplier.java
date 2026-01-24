package io.kneo.broadcaster.service.live;

import io.kneo.broadcaster.model.ScenePrompt;
import io.kneo.broadcaster.model.aiagent.AiAgent;
import io.kneo.broadcaster.model.cnst.GeneratedContentStatus;
import io.kneo.broadcaster.model.cnst.LanguageTag;
import io.kneo.broadcaster.model.soundfragment.SoundFragment;
import io.kneo.broadcaster.model.stream.IStream;
import io.kneo.broadcaster.model.stream.LiveScene;
import io.kneo.broadcaster.model.stream.PendingSongEntry;
import io.kneo.broadcaster.service.soundfragment.SoundFragmentService;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public abstract class StreamSupplier {
    private static final Logger LOGGER = LoggerFactory.getLogger(StreamSupplier.class);

    @Inject
    GeneratedNewsService generatedNewsService;

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
            LiveScene activeEntry,
            UUID brandId,
            SoundFragmentService soundFragmentService,
            AiAgent agent,
            IStream stream,
            LanguageTag broadcastingLanguage
    ) {
        activeEntry.setGeneratedContentStatus(GeneratedContentStatus.PROCESSING);
        // Get prompt ID early as it's needed in error handling
        List<ScenePrompt> contentPrompts = activeEntry.getContentPrompts();
        if (contentPrompts == null || contentPrompts.isEmpty()) {
            LOGGER.error("No content prompts found for GENERATED scene, scene: {}", activeEntry.getSceneTitle());
            activeEntry.setGeneratedContentStatus(GeneratedContentStatus.ERROR);
            return Uni.createFrom().item(List.of());
        }
        UUID promptId = contentPrompts.getFirst().getPromptId();
        
        // For news scenes, check if we already have a generated fragment
        if (activeEntry.getGeneratedFragmentId() != null) {
            LOGGER.info("Scene {} already has generated fragment {}, checking if valid", 
                    activeEntry.getSceneTitle(), activeEntry.getGeneratedFragmentId());
            
            // Check if the fragment actually exists and is valid
            return soundFragmentService.getById(activeEntry.getGeneratedFragmentId())
                    .flatMap(fragment -> {
                        if (fragment.getExpiresAt() != null && fragment.getExpiresAt().isBefore(java.time.LocalDateTime.now())) {
                            LOGGER.info("Fragment {} expired at {}, regenerating", 
                                    activeEntry.getGeneratedFragmentId(), fragment.getExpiresAt());
                            return generateNewContent(promptId, agent, stream, brandId, activeEntry, broadcastingLanguage);
                        }
                        
                        LOGGER.info("Reusing valid fragment {} for scene {}", 
                                activeEntry.getGeneratedFragmentId(), activeEntry.getSceneTitle());
                        activeEntry.setGeneratedContentStatus(GeneratedContentStatus.REUSING);
                        return Uni.createFrom().item(List.of(fragment));
                    })
                    .onFailure().recoverWithUni(error -> {
                        LOGGER.warn("Fragment {} not found (may be deleted), regenerating", 
                                activeEntry.getGeneratedFragmentId());
                        return generateNewContent(promptId, agent, stream, brandId, activeEntry, broadcastingLanguage);
                    });
        }

        // If no fragment ID, try to reuse from another scene with same prompt (today's news)
        LiveScene sourceScene = stream.getStreamAgenda().getLiveScenes().stream()
                .filter(scene -> !scene.getSceneId().equals(activeEntry.getSceneId()))
                .filter(scene -> scene.getGeneratedFragmentId() != null)
                .filter(scene -> scene.getContentPrompts() != null && !scene.getContentPrompts().isEmpty())
                .filter(scene -> scene.getContentPrompts().getFirst().getPromptId().equals(promptId))
                .filter(scene -> scene.getGeneratedContentTimestamp() != null 
                        && scene.getGeneratedContentTimestamp().toLocalDate().equals(java.time.LocalDate.now()))
                .findFirst()
                .orElse(null);

        if (sourceScene != null) {
            UUID existingFragmentId = sourceScene.getGeneratedFragmentId();
            
            return soundFragmentService.getById(existingFragmentId)
                    .flatMap(fragment -> {
                        if (fragment.getExpiresAt() != null && fragment.getExpiresAt().isBefore(java.time.LocalDateTime.now())) {
                            LOGGER.info("Fragment {} expired at {}, re-generating", existingFragmentId, fragment.getExpiresAt());
                            return generateNewContent(promptId, agent, stream, brandId, activeEntry, broadcastingLanguage);
                        }
                        
                        LOGGER.info("Reusing today's news fragment {} from scene '{}' for scene '{}'", 
                                existingFragmentId, sourceScene.getSceneTitle(), activeEntry.getSceneTitle());
                        activeEntry.setGeneratedFragmentId(existingFragmentId);
                        activeEntry.setGeneratedContentTimestamp(sourceScene.getGeneratedContentTimestamp());
                        activeEntry.setGeneratedContentStatus(GeneratedContentStatus.REUSING);
                        return Uni.createFrom().item(List.of(fragment));
                    })
                    .onFailure().recoverWithUni(error -> {
                        LOGGER.warn("Fragment {} not found (may be deleted), re-generating", existingFragmentId);
                        return generateNewContent(promptId, agent, stream, brandId, activeEntry, broadcastingLanguage);
                    });
        }

        return generateNewContent(promptId, agent, stream, brandId, activeEntry, broadcastingLanguage);
    }

    private Uni<List<SoundFragment>> generateNewContent(
            UUID promptId,
            AiAgent agent,
            IStream stream,
            UUID brandId,
            LiveScene activeEntry,
            LanguageTag broadcastingLanguage
    ) {
        LOGGER.info("Generating new content for scene: {} prompt: {}", activeEntry.getSceneTitle(), promptId);
        activeEntry.setGeneratedContentStatus(GeneratedContentStatus.PENDING);
        
        return generatedNewsService.generateNewsFragment(promptId, agent, stream, brandId, activeEntry, broadcastingLanguage)
                .map(List::of)
                .onFailure().recoverWithUni(error -> {
                    LOGGER.error("Failed to generate content for prompt {}", promptId, error);
                    activeEntry.setGeneratedContentStatus(GeneratedContentStatus.ERROR);
                    return Uni.createFrom().item(List.of());
                });
    }
}
