package io.kneo.broadcaster.service.live;

import io.kneo.broadcaster.dto.aihelper.SongPromptDTO;
import io.kneo.broadcaster.dto.dashboard.AiDjStatsDTO;
import io.kneo.broadcaster.model.Prompt;
import io.kneo.broadcaster.model.ScenePrompt;
import io.kneo.broadcaster.model.aiagent.AiAgent;
import io.kneo.broadcaster.model.cnst.LanguageTag;
import io.kneo.broadcaster.model.soundfragment.SoundFragment;
import io.kneo.broadcaster.model.stream.LiveScene;
import io.kneo.broadcaster.model.stream.RadioStream;
import io.kneo.broadcaster.service.PromptService;
import io.kneo.broadcaster.service.SceneService;
import io.kneo.broadcaster.service.playlist.SongSupplier;
import io.kneo.broadcaster.service.soundfragment.SoundFragmentService;
import io.kneo.broadcaster.util.AiHelperUtils;
import io.kneo.core.model.user.SuperUser;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.tuples.Tuple2;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

@ApplicationScoped
public class RadioStreamSupplier extends StreamSupplier {

    @FunctionalInterface
    public interface MessageSink {
        void add(String stationSlug, AiDjStatsDTO.MessageType type, String message);
    }

    private final PromptService promptService;
    private final DraftFactory draftFactory;
    private final SceneService sceneService;
    private final SongSupplier songSupplier;
    private final SoundFragmentService soundFragmentService;
    private final JinglePlaybackHandler jinglePlaybackHandler;

    @Inject
    public RadioStreamSupplier(PromptService promptService, DraftFactory draftFactory, SceneService sceneService, SongSupplier songSupplier, SoundFragmentService soundFragmentService, JinglePlaybackHandler jinglePlaybackHandler) {
        this.promptService = promptService;
        this.draftFactory = draftFactory;
        this.sceneService = sceneService;
        this.songSupplier = songSupplier;
        this.soundFragmentService = soundFragmentService;
        this.jinglePlaybackHandler = jinglePlaybackHandler;
    }

    public Uni<Tuple2<List<SongPromptDTO>, String>> fetchStuffForRadioStream(
            RadioStream stream,
            AiAgent agent,
            LanguageTag broadcastingLanguage,
            String additionalInstruction,
            MessageSink messageSink
    ) {
        LiveScene activeScene = stream.findActiveScene();
        if (activeScene == null) {
            return Uni.createFrom().failure(
                new IllegalStateException("No active scene found for RadioStream: " + stream.getSlugName())
            );
        }

        String currentSceneTitle = activeScene.getSceneTitle();

        Uni<List<SoundFragment>> songsUni = getSongsFromSceneEntry(
                activeScene, stream.getSlugName(), stream.getMasterBrand().getId(), songSupplier, soundFragmentService);

        return songsUni.flatMap(songs ->
                sceneService.getById(activeScene.getSceneId(), SuperUser.build())
                        .chain(scene -> {
                            double effectiveTalkativity = scene.getTalkativity();
                            double rate = stream.getPopularityRate();
                            if (rate < 4.0) {
                                double factor = Math.max(0.0, Math.min(1.0, rate / 5.0));
                                effectiveTalkativity =
                                        Math.max(0.0, Math.min(1.0, effectiveTalkativity * factor));
                            }


                            if (AiHelperUtils.shouldPlayJingle(effectiveTalkativity)) {
                                jinglePlaybackHandler.handleJinglePlayback(stream, scene);
                                return Uni.createFrom().item(() -> null);
                            }

                            List<UUID> enabledPrompts = scene.getPrompts() != null
                                    ? scene.getPrompts().stream()
                                    .filter(ScenePrompt::isActive)
                                    .map(ScenePrompt::getPromptId)
                                    .toList()
                                    : List.of();

                            if (enabledPrompts.isEmpty()) {
                                messageSink.add(
                                        stream.getSlugName(),
                                        AiDjStatsDTO.MessageType.WARNING,
                                        String.format("Active scene '%s' has no enabled prompts", currentSceneTitle)
                                );
                                return Uni.createFrom().item(() -> null);
                            }

                            List<Uni<Prompt>> promptUnis = enabledPrompts.stream()
                                    .map(masterId ->
                                            promptService.getById(masterId, SuperUser.build())
                                                    .flatMap(masterPrompt -> {
                                                        if (masterPrompt.getLanguageTag() == broadcastingLanguage) {
                                                            return Uni.createFrom().item(masterPrompt);
                                                        }
                                                        return promptService
                                                                .findByMasterAndLanguage(masterId, broadcastingLanguage, false)
                                                                .map(p -> p != null ? p : masterPrompt);
                                                    })
                                    )
                                    .toList();

                            return Uni.join().all(promptUnis).andFailFast()
                                    .flatMap(prompts -> {
                                        Random random = new Random();
                                        List<Uni<SongPromptDTO>> songPromptUnis = songs.stream()
                                                .map(song -> {
                                                    Prompt selectedPrompt = prompts.get(random.nextInt(prompts.size()));
                                                    return draftFactory.createDraft(
                                                                    song,
                                                                    agent,
                                                                    stream,
                                                                    selectedPrompt.getDraftId(),
                                                                    LanguageTag.EN_US,
                                                                    Map.of()
                                                            )
                                                            .map(draft -> new SongPromptDTO(
                                                                    song.getId(),
                                                                    draft,
                                                                    selectedPrompt.getPrompt() + additionalInstruction,
                                                                    selectedPrompt.getPromptType(),
                                                                    agent.getLlmType(),
                                                                    agent.getSearchEngineType(),
                                                                    activeScene.getScheduledStartTime().toLocalTime(),
                                                                    false,
                                                                    selectedPrompt.isPodcast()
                                                            ));
                                                })
                                                .toList();

                                        return Uni.join().all(songPromptUnis).andFailFast()
                                                .map(result -> Tuple2.of(result, currentSceneTitle));
                                    });
                        }));
    }
}
