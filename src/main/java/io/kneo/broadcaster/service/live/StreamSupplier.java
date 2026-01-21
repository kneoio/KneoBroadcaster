package io.kneo.broadcaster.service.live;

import io.kneo.broadcaster.model.ScenePrompt;
import io.kneo.broadcaster.model.aiagent.AiAgent;
import io.kneo.broadcaster.model.cnst.GeneratedContentStatus;
import io.kneo.broadcaster.model.soundfragment.SoundFragment;
import io.kneo.broadcaster.model.stream.IStream;
import io.kneo.broadcaster.model.stream.LiveScene;
import io.kneo.broadcaster.model.stream.ScheduledSongEntry;
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
            List<ScheduledSongEntry> scheduledSongs,
            Set<UUID> excludeIds
    ) {
        List<ScheduledSongEntry> available = scheduledSongs.stream()
                .filter(e -> !excludeIds.contains(e.getSoundFragment().getId()))
                .toList();

        if (available.isEmpty()) {
            return List.of();
        }

        int take = available.size() >= 2 && new Random().nextDouble() < 0.7 ? 2 : 1;
        return available.stream()
                .limit(take)
                .map(ScheduledSongEntry::getSoundFragment)
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
        List<ScheduledSongEntry> existingSongs = activeEntry.getSongs();
        if (!existingSongs.isEmpty()) {
            LOGGER.info("Using existing generated news fragment for scene: {}", activeEntry.getSceneTitle());
            return Uni.createFrom().item(
                    existingSongs.stream()
                            .map(ScheduledSongEntry::getSoundFragment)
                            .toList()
            );
        }

        List<ScenePrompt> prompts = activeEntry.getPrompts();
        if (prompts == null || prompts.isEmpty()) {
            LOGGER.error("No prompts found for GENERATED scene");
            return Uni.createFrom().item(List.of());
        }

        UUID promptId = prompts.getFirst().getPromptId();

        LiveScene sourceScene = stream.getStreamAgenda().getLiveScenes().stream()
                .filter(scene -> !scene.getSceneId().equals(activeEntry.getSceneId()))
                .filter(scene -> scene.getGeneratedFragmentId() != null)
                .filter(scene -> scene.getPrompts() != null && !scene.getPrompts().isEmpty())
                .filter(scene -> scene.getPrompts().getFirst().getPromptId().equals(promptId))
                .findFirst()
                .orElse(null);

        if (sourceScene != null) {
            UUID existingFragmentId = sourceScene.getGeneratedFragmentId();
            LOGGER.info("Reusing fragment {} from another scene with same prompt {}", existingFragmentId, promptId);
            activeEntry.setGeneratedFragmentId(existingFragmentId);
            activeEntry.setGeneratedContentTimestamp(sourceScene.getGeneratedContentTimestamp());
            activeEntry.setGeneratedContentStatus(GeneratedContentStatus.REUSING);
            return soundFragmentService.getById(existingFragmentId).map(List::of);
        }

        LOGGER.info("No existing fragment found for prompt {}, generating new content", promptId);
        activeEntry.setGeneratedContentStatus(GeneratedContentStatus.PENDING);
        return generatedNewsService.generateNewsFragment(promptId, agent, stream, brandId, activeEntry, broadcastingLanguage)
                .map(List::of);
    }
}
