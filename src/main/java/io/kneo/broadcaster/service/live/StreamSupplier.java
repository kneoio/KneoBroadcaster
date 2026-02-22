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

        int take = available.size() >= 2 && new Random().nextDouble() < 0.6 ? 2 : 1;
        //int take = 1;
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
        
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        LocalDateTime endOfDay = startOfDay.plusDays(1);
        
        return soundFragmentService.findByArtistAndDate(promptId.toString(), startOfDay, endOfDay)
                .flatMap(existingFragment -> {
                    if (existingFragment != null) {
                        if (existingFragment.getExpiresAt() != null && existingFragment.getExpiresAt().isBefore(LocalDateTime.now())) {
                            LOGGER.info("Fragment {} expired at {}, regenerating", existingFragment.getId(), existingFragment.getExpiresAt());
                            return generateContent(promptId, agent, stream, brandId, liveScene, airLanguage);
                        }
                        LOGGER.info("Reusing existing fragment {} for scene {} (prompt: {})",
                                existingFragment.getId(), liveScene.getSceneTitle(), promptId);
                        liveScene.setGeneratedContentStatus(GeneratedContentStatus.REUSING);
                        return Uni.createFrom().item(List.of(existingFragment));
                    }
                    return generateContent(promptId, agent, stream, brandId, liveScene, airLanguage);
                });
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
