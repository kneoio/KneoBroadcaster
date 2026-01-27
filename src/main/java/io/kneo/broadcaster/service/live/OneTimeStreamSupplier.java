package io.kneo.broadcaster.service.live;

import io.kneo.broadcaster.dto.aihelper.SongPromptDTO;
import io.kneo.broadcaster.dto.queue.AddToQueueDTO;
import io.kneo.broadcaster.model.FileMetadata;
import io.kneo.broadcaster.model.Prompt;
import io.kneo.broadcaster.model.ScenePrompt;
import io.kneo.broadcaster.model.aiagent.AiAgent;
import io.kneo.broadcaster.model.cnst.LanguageTag;
import io.kneo.broadcaster.model.cnst.StreamStatus;
import io.kneo.broadcaster.model.soundfragment.SoundFragment;
import io.kneo.broadcaster.model.stream.LiveScene;
import io.kneo.broadcaster.model.stream.OneTimeStream;
import io.kneo.broadcaster.model.stream.PendingSongEntry;
import io.kneo.broadcaster.service.PromptService;
import io.kneo.broadcaster.service.SceneService;
import io.kneo.broadcaster.service.live.scripting.DraftFactory;
import io.kneo.broadcaster.service.manipulation.mixing.MergingType;
import io.kneo.broadcaster.service.playlist.PlaylistManager;
import io.kneo.broadcaster.service.soundfragment.SoundFragmentService;
import io.kneo.core.model.user.SuperUser;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.tuples.Tuple2;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class OneTimeStreamSupplier extends StreamSupplier {

    private static final Logger LOGGER = LoggerFactory.getLogger(OneTimeStreamSupplier.class);

    private final PromptService promptService;
    private final DraftFactory draftFactory;
    private final SceneService sceneService;
    private final SoundFragmentService soundFragmentService;

    @Inject
    public OneTimeStreamSupplier(
            PromptService promptService,
            DraftFactory draftFactory,
            SceneService sceneService,
            SoundFragmentService soundFragmentService
    ) {
        this.promptService = promptService;
        this.draftFactory = draftFactory;
        this.sceneService = sceneService;
        this.soundFragmentService = soundFragmentService;
    }

    public Uni<Tuple2<List<SongPromptDTO>, String>> fetchOneTimeStreamPrompt(
            OneTimeStream stream,
            AiAgent agent,
            LanguageTag broadcastingLanguage,
            String additionalInstruction
    ) {
        LiveScene activeEntry = stream.findActiveScene(0);

        if (activeEntry == null) {
            UUID prevSceneId = stream.getCurrentSceneId();
            if (prevSceneId != null) {
                LiveScene prev = findSceneById(stream, prevSceneId);
                if (prev != null && prev.getActualEndTime() == null) {
                    prev.setActualEndTime(LocalDateTime.now());
                }
            }

            if (stream.isCompleted()) {
                if (stream.getScheduledOfflineAt() == null) {
                    int lastSceneDuration = stream.getLastDeliveredSongsDuration();
                    int delaySeconds = lastSceneDuration + (5 * 60);
                    LocalDateTime offlineAt = LocalDateTime.now().plusSeconds(delaySeconds);
                    stream.setScheduledOfflineAt(offlineAt);
                    LOGGER.info("Stream {} completed - scheduled to go offline at {} (delay: {} seconds)", 
                            stream.getSlugName(), offlineAt, delaySeconds);
                } else if (LocalDateTime.now().isAfter(stream.getScheduledOfflineAt())) {
                    stream.setStatus(StreamStatus.FINISHED);
                    LOGGER.info("Stream {} finished - scheduled time reached", stream.getSlugName());
                }
            }
            return Uni.createFrom().item(() -> null);
        }

        UUID activeSceneId = activeEntry.getSceneId();
        UUID currentSceneId = stream.getCurrentSceneId();

        if (currentSceneId != null && !currentSceneId.equals(activeSceneId)) {
            LiveScene prev = findSceneById(stream, currentSceneId);
            if (prev != null && prev.getActualEndTime() == null) {
                prev.setActualEndTime(LocalDateTime.now());
            }
            stream.clearSceneState(currentSceneId);
            stream.setCurrentSceneId(activeSceneId);
        }

        if (stream.getCurrentSceneId() == null) {
            stream.setCurrentSceneId(activeSceneId);
        }

        if (activeEntry.getActualStartTime() == null) {
            activeEntry.setActualStartTime(LocalDateTime.now());
        }

        Set<UUID> fetchedSongsInScene =
                stream.getFetchedSongsInScene(activeSceneId);

        String currentSceneTitle = activeEntry.getSceneTitle();
        Map<String, Object> userVariables = stream.getUserVariables();

        Uni<List<SoundFragment>> songsUni;
        List<PendingSongEntry> scheduledSongs = activeEntry.getSongs();

        if (!scheduledSongs.isEmpty()) {
            List<SoundFragment> pickedSongs = pickSongsFromScheduled(scheduledSongs, fetchedSongsInScene);

            if (pickedSongs.isEmpty()) {
                // Check if scene should end by time or continue waiting
                java.time.LocalDateTime now = java.time.LocalDateTime.now();
                if (now.isAfter(activeEntry.getScheduledEndTime())) {
                    // Scene time is over, end it
                    activeEntry.setActualEndTime(now);
                    stream.clearSceneState(activeSceneId);
                    return Uni.createFrom().item(() -> null);
                } else {
                    // Songs exhausted but time remains, wait for next cycle
                    return Uni.createFrom().item(() -> null);
                }
            }

            songsUni = Uni.createFrom().item(pickedSongs);
        } else {
            songsUni = generateContentForScene(
                    activeEntry,
                    stream.getMasterBrand().getId(),
                    soundFragmentService,
                    agent,
                    stream,
                    LanguageTag.EN_US
            );
        }

        return songsUni.flatMap(songs -> {
            if (songs.isEmpty()) {
                return Uni.createFrom().item(() -> null);
            }

            return sceneService.getById(activeEntry.getSceneId(), SuperUser.build())
                    .chain(scene -> {
                        List<UUID> introPromptIds = scene.getIntroPrompts() == null
                                ? List.of()
                                : scene.getIntroPrompts().stream()
                                .filter(ScenePrompt::isActive)
                                .map(ScenePrompt::getPromptId)
                                .toList();

                        if (introPromptIds.isEmpty()) {
                            LOGGER.info("Scene '{}' has no prompts - queueing songs directly", activeEntry.getSceneTitle());
                            return queueSongsDirectly(stream, songs, fetchedSongsInScene)
                                    .map(success -> null);
                        }

                        List<Uni<Prompt>> promptUnis = introPromptIds.stream()
                                .map(id ->
                                        promptService.getById(id, SuperUser.build())
                                                .flatMap(master -> {
                                                    if (master.getLanguageTag() == broadcastingLanguage) {
                                                        return Uni.createFrom().item(master);
                                                    }
                                                    return promptService
                                                            .findByMasterAndLanguage(id, broadcastingLanguage, false)
                                                            .map(p -> p != null ? p : master);
                                                })
                                )
                                .toList();

                        return Uni.join().all(promptUnis).andFailFast()
                                .flatMap(prompts -> {
                                    Random random = new Random();

                                    List<Uni<SongPromptDTO>> songPromptUnis = songs.stream()
                                            .map(song -> {
                                                Prompt selected = prompts.get(random.nextInt(prompts.size()));
                                                int songDuration = song.getLength() != null ? (int) song.getLength().toSeconds() : 180;
                                                return draftFactory.createDraft(
                                                                song,
                                                                agent,
                                                                stream,
                                                                selected.getDraftId(),
                                                                LanguageTag.EN_US,
                                                                userVariables
                                                        )
                                                        .map(draft -> {
                                                            SongPromptDTO dto = new SongPromptDTO(
                                                                    song.getId(),
                                                                    draft,
                                                                    selected.getPrompt() + additionalInstruction,
                                                                    selected.getPromptType(),
                                                                    agent.getLlmType(),
                                                                    activeEntry.getScheduledStartTime().toLocalTime(),
                                                                    selected.isPodcast(),
                                                                    selected.getTitle()
                                                            );
                                                            dto.setSongDurationSeconds(songDuration);
                                                            return dto;
                                                        });
                                            })
                                            .toList();

                                    return Uni.join().all(songPromptUnis).andFailFast()
                                            .map(result -> {
                                                songs.forEach(s -> fetchedSongsInScene.add(s.getId()));
                                                int totalDuration = result.stream()
                                                        .mapToInt(SongPromptDTO::getSongDurationSeconds)
                                                        .sum();
                                                stream.setLastDeliveredSongsDuration(totalDuration);
                                                return Tuple2.of(result, currentSceneTitle);
                                            });
                                });
                    });
        });
    }

    private LiveScene findSceneById(OneTimeStream stream, UUID sceneId) {
        return stream.getStreamAgenda().getLiveScenes().stream()
                .filter(s -> s.getSceneId().equals(sceneId))
                .findFirst()
                .orElse(null);
    }

    private Uni<Boolean> queueSongsDirectly(OneTimeStream stream, List<SoundFragment> songs, Set<UUID> fetchedSongsInScene) {
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
