package io.kneo.broadcaster.service.live;

import io.kneo.broadcaster.dto.aihelper.SongPromptDTO;
import io.kneo.broadcaster.dto.dashboard.AiDjStatsDTO;
import io.kneo.broadcaster.dto.queue.AddToQueueDTO;
import io.kneo.broadcaster.model.FileMetadata;
import io.kneo.broadcaster.model.Prompt;
import io.kneo.broadcaster.model.ScenePrompt;
import io.kneo.broadcaster.model.aiagent.AiAgent;
import io.kneo.broadcaster.model.cnst.LanguageTag;
import io.kneo.broadcaster.model.soundfragment.SoundFragment;
import io.kneo.broadcaster.model.stream.LiveScene;
import io.kneo.broadcaster.model.stream.PendingSongEntry;
import io.kneo.broadcaster.model.stream.RadioStream;
import io.kneo.broadcaster.service.PromptService;
import io.kneo.broadcaster.service.SceneService;
import io.kneo.broadcaster.service.manipulation.mixing.MergingType;
import io.kneo.broadcaster.service.playlist.PlaylistManager;
import io.kneo.broadcaster.service.soundfragment.SoundFragmentService;
import io.kneo.broadcaster.util.AiHelperUtils;
import io.kneo.core.model.user.SuperUser;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.tuples.Tuple2;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
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
    private final SoundFragmentService soundFragmentService;
    private final JinglePlaybackHandler jinglePlaybackHandler;

    @Inject
    public RadioStreamSupplier(PromptService promptService, DraftFactory draftFactory, SceneService sceneService, SoundFragmentService soundFragmentService, JinglePlaybackHandler jinglePlaybackHandler) {
        this.promptService = promptService;
        this.draftFactory = draftFactory;
        this.sceneService = sceneService;
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

        UUID activeSceneId = activeScene.getSceneId();
        String currentSceneTitle = activeScene.getSceneTitle();

        Set<UUID> fetchedSongsInScene = stream.getFetchedSongsInScene(activeSceneId);

        List<PendingSongEntry> scheduledSongs = activeScene.getSongs();
        if (scheduledSongs.isEmpty()) {
            messageSink.add(
                    stream.getSlugName(),
                    AiDjStatsDTO.MessageType.ERROR,
                    String.format("Scene '%s' has no predefined songs", currentSceneTitle)
            );
            return Uni.createFrom().item(() -> null);
        }

        List<SoundFragment> songs = pickSongsFromScheduled(scheduledSongs, fetchedSongsInScene);

        if (songs.isEmpty()) {
            activeScene.setActualEndTime(java.time.LocalDateTime.now());
            stream.clearSceneState(activeSceneId);
            return Uni.createFrom().item(() -> null);
        }

        return Uni.createFrom().item(songs).flatMap(songList ->
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
                                jinglePlaybackHandler.handleJinglePlayback(stream, scene, activeScene, fetchedSongsInScene);
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
                                        AiDjStatsDTO.MessageType.INFO,
                                        String.format("Scene '%s' has no prompts - queueing songs directly", currentSceneTitle)
                                );
                                return queueSongsDirectly(stream, songList, fetchedSongsInScene)
                                        .map(success -> null);
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
                                        List<Uni<SongPromptDTO>> songPromptUnis = songList.stream()
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
                                                                    selectedPrompt.isPodcast()
                                                            ));
                                                })
                                                .toList();

                                        return Uni.join().all(songPromptUnis).andFailFast()
                                                .map(result -> {
                                                    songList.forEach(s -> fetchedSongsInScene.add(s.getId()));
                                                    return Tuple2.of(result, currentSceneTitle);
                                                });
                                    });
                        })
        );
    }

    private Uni<Boolean> queueSongsDirectly(RadioStream stream, List<SoundFragment> songs, Set<UUID> fetchedSongsInScene) {
        PlaylistManager playlistManager = stream.getStreamManager().getPlaylistManager();
        
        return Multi.createFrom().iterable(songs)
                .onItem().transformToUniAndConcatenate(song -> {
                    List<FileMetadata> metadataList = song.getFileMetadataList();
                    if (metadataList == null || metadataList.isEmpty()) {
                        return Uni.createFrom().item(false);
                    }
                    FileMetadata metadata = metadataList.getFirst();
                    
                    return soundFragmentService.getFileBySlugName(
                                    song.getId(),
                                    metadata.getSlugName(),
                                    SuperUser.build()
                            )
                            .chain(fetchedMetadata -> fetchedMetadata.materializeFileStream(playlistManager.getClass().getName()))
                            .chain(tempFilePath -> {
                                AddToQueueDTO queueDTO = new AddToQueueDTO();
                                queueDTO.setPriority(15);
                                queueDTO.setMergingMethod(MergingType.NOT_MIXED);
                                
                                return playlistManager.addFragmentToSlice(
                                        song,
                                        15,
                                        stream.getBitRate(),
                                        MergingType.NOT_MIXED,
                                        queueDTO
                                ).onItem().invoke(() -> fetchedSongsInScene.add(song.getId()));
                            })
                            .onFailure().recoverWithItem(false);
                })
                .collect().asList()
                .map(results -> results.stream().anyMatch(r -> r));
    }
}
