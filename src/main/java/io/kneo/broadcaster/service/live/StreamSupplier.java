package io.kneo.broadcaster.service.live;

import io.kneo.broadcaster.model.ScenePrompt;
import io.kneo.broadcaster.model.aiagent.AiAgent;
import io.kneo.broadcaster.model.cnst.GeneratedContentStatus;
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
            io.kneo.broadcaster.model.cnst.LanguageTag broadcastingLanguage
    ) {
        List<PendingSongEntry> existingSongs = activeEntry.getSongs();
        if (!existingSongs.isEmpty()) {
            LOGGER.info("Using existing generated news fragment for scene: {}", activeEntry.getSceneTitle());
            return Uni.createFrom().item(
                    existingSongs.stream()
                            .map(PendingSongEntry::getSoundFragment)
                            .toList()
            );
        }

        List<ScenePrompt> contentPrompts = activeEntry.getContentPrompts();
        if (contentPrompts == null || contentPrompts.isEmpty()) {
            LOGGER.error("No content prompts found for GENERATED scene");
            activeEntry.setGeneratedContentStatus(GeneratedContentStatus.ERROR);
            return Uni.createFrom().item(List.of());
        }

        UUID promptId = contentPrompts.getFirst().getPromptId();

        LiveScene sourceScene = stream.getStreamAgenda().getLiveScenes().stream()
                .filter(scene -> !scene.getSceneId().equals(activeEntry.getSceneId()))
                .filter(scene -> scene.getGeneratedFragmentId() != null)
                .filter(scene -> scene.getContentPrompts() != null && !scene.getContentPrompts().isEmpty())
                .filter(scene -> scene.getContentPrompts().getFirst().getPromptId().equals(promptId))
                .findFirst()
                .orElse(null);

        if (sourceScene != null) {
            UUID existingFragmentId = sourceScene.getGeneratedFragmentId();
            
            return soundFragmentService.getById(existingFragmentId)
                    .flatMap(fragment -> {
                        if (fragment.getExpiresAt() != null && fragment.getExpiresAt().isBefore(java.time.LocalDateTime.now())) {
                            LOGGER.info("Fragment {} expired at {}, regenerating", existingFragmentId, fragment.getExpiresAt());
                            return generateNewContent(promptId, agent, stream, brandId, activeEntry, broadcastingLanguage);
                        }
                        
                        LOGGER.info("Reusing valid fragment {} from scene with same prompt {}", existingFragmentId, promptId);
                        activeEntry.setGeneratedFragmentId(existingFragmentId);
                        activeEntry.setGeneratedContentTimestamp(sourceScene.getGeneratedContentTimestamp());
                        activeEntry.setGeneratedContentStatus(GeneratedContentStatus.REUSING);
                        return Uni.createFrom().item(List.of(fragment));
                    })
                    .onFailure().recoverWithUni(error -> {
                        LOGGER.warn("Fragment {} not found (may be expired/deleted), regenerating", existingFragmentId);
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
            io.kneo.broadcaster.model.cnst.LanguageTag broadcastingLanguage
    ) {
        LOGGER.info("Generating new content for prompt {}", promptId);
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
