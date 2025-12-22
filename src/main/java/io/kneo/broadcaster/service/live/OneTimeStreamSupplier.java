package io.kneo.broadcaster.service.live;

import io.kneo.broadcaster.dto.aihelper.SongPromptDTO;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.dto.dashboard.AiDjStatsDTO;
import io.kneo.broadcaster.model.Action;
import io.kneo.broadcaster.model.Prompt;
import io.kneo.broadcaster.model.aiagent.AiAgent;
import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.model.soundfragment.SoundFragment;
import io.kneo.broadcaster.model.stream.OneTimeStream;
import io.kneo.broadcaster.model.stream.SceneScheduleEntry;
import io.kneo.broadcaster.model.stream.ScheduledSongEntry;
import io.kneo.broadcaster.service.PromptService;
import io.kneo.broadcaster.service.SceneService;
import io.kneo.broadcaster.service.playlist.SongSupplier;
import io.kneo.broadcaster.service.soundfragment.SoundFragmentService;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.SuperUser;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.tuples.Tuple2;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class OneTimeStreamSupplier extends StreamSupplier {

    @FunctionalInterface
    public interface MessageSink {
        void add(String stationSlug, AiDjStatsDTO.MessageType type, String message);
    }

    private final PromptService promptService;
    private final DraftFactory draftFactory;
    private final SceneService sceneService;
    private final SongSupplier songSupplier;
    private final SoundFragmentService soundFragmentService;
    private final Map<UUID, Set<UUID>> fetchedSongsByScene = new HashMap<>();
    private UUID currentSceneId = null;

    @Inject
    public OneTimeStreamSupplier(
            PromptService promptService,
            DraftFactory draftFactory,
            SceneService sceneService,
            SongSupplier songSupplier,
            SoundFragmentService soundFragmentService
    ) {
        this.promptService = promptService;
        this.draftFactory = draftFactory;
        this.sceneService = sceneService;
        this.songSupplier = songSupplier;
        this.soundFragmentService = soundFragmentService;
    }

    public Uni<Tuple2<List<SongPromptDTO>, String>> fetchPromptForOneTimeStream(
            OneTimeStream stream,
            AiAgent agent,
            LanguageCode broadcastingLanguage,
            String additionalInstruction,
            MessageSink messageSink
    ) {
        String currentStreamSlugName = stream.getSlugName();
        SceneScheduleEntry activeEntry = stream.findActiveSceneEntry();

        if (activeEntry == null) {
            if (stream.isCompleted()) {
                stream.setStatus(RadioStationStatus.OFF_LINE);
                messageSink.add(
                        currentStreamSlugName,
                        AiDjStatsDTO.MessageType.INFO,
                        "Stream completed - all scenes played"
                );
            }
            return Uni.createFrom().item(() -> null);
        }

        UUID activeSceneId = activeEntry.getSceneId();
        if (currentSceneId != null && !currentSceneId.equals(activeSceneId)) {
            fetchedSongsByScene.remove(currentSceneId);
            currentSceneId = activeSceneId;
        }
        if (currentSceneId == null) {
            currentSceneId = activeSceneId;
        }

        Set<UUID> fetchedSongsInScene = fetchedSongsByScene.computeIfAbsent(activeSceneId, k -> new HashSet<>());

        String currentSceneTitle = activeEntry.getSceneTitle();
        Map<String, Object> userVariables = stream.getUserVariables();

        Uni<List<SoundFragment>> songsUni;
        List<ScheduledSongEntry> scheduledSongs = activeEntry.getSongs();

        if (!scheduledSongs.isEmpty()) {
            List<ScheduledSongEntry> availableEntries = scheduledSongs.stream()
                    .filter(entry -> !fetchedSongsInScene.contains(entry.getSoundFragment().getId()))
                    .toList();

            if (availableEntries.isEmpty()) {
                List<SceneScheduleEntry> allScenes = stream.getStreamSchedule().getSceneScheduleEntries();
                boolean isLastScene = allScenes.indexOf(activeEntry) == allScenes.size() - 1;
                
                if (isLastScene) {
                    stream.setStatus(RadioStationStatus.OFF_LINE);
                    messageSink.add(
                            currentStreamSlugName,
                            AiDjStatsDTO.MessageType.INFO,
                            String.format("Last scene '%s' completed - stream finished", currentSceneTitle)
                    );
                } else {
                    messageSink.add(
                            currentStreamSlugName,
                            AiDjStatsDTO.MessageType.INFO,
                            String.format("All songs exhausted for scene '%s', waiting for next scene", currentSceneTitle)
                    );
                }
                return Uni.createFrom().item(() -> null);
            }

            Random random = new Random();
            int songsToReturn = Math.min(availableEntries.size(), random.nextInt(2) + 1);
            List<SoundFragment> selectedSongs = availableEntries.stream()
                    .limit(songsToReturn)
                    .peek(entry -> fetchedSongsInScene.add(entry.getSoundFragment().getId()))
                    .map(ScheduledSongEntry::getSoundFragment)
                    .toList();

            songsUni = Uni.createFrom().item(selectedSongs);
        } else {
            songsUni = getSongsFromEntry(
                    activeEntry,
                    currentStreamSlugName,
                    stream.getMasterBrand().getId(),
                    songSupplier,
                    soundFragmentService
            ).flatMap(songs -> {
                if (songs.isEmpty()) {
                    messageSink.add(
                            currentStreamSlugName,
                            AiDjStatsDTO.MessageType.WARNING,
                            String.format("No songs found for scene '%s' sourcing, falling back to random brand songs", currentSceneTitle)
                    );
                    int songCount = new Random().nextDouble() < 0.7 ? 1 : 2;
                    return songSupplier.getNextSong(stream.getMasterBrand().getSlugName(), PlaylistItemType.SONG, songCount);
                }
                return Uni.createFrom().item(songs);
            });
        }

        return songsUni.flatMap(songs -> {
            if (songs.isEmpty()) {
                messageSink.add(
                        currentStreamSlugName,
                        AiDjStatsDTO.MessageType.WARNING,
                        String.format("No unplayed songs available for scene '%s'", currentSceneTitle)
                );
                return Uni.createFrom().item(() -> null);
            }

            return sceneService.getById(activeEntry.getSceneId(), SuperUser.build())
                    .chain(scene -> {
                        List<UUID> promptIds = scene.getPrompts() != null
                                ? scene.getPrompts().stream()
                                .filter(Action::isActive)
                                .map(Action::getPromptId)
                                .toList()
                                : List.of();

                        if (promptIds.isEmpty()) {
                            messageSink.add(
                                    currentStreamSlugName,
                                    AiDjStatsDTO.MessageType.WARNING,
                                    String.format("Active scene '%s' has no prompts", currentSceneTitle)
                            );
                            return Uni.createFrom().item(() -> null);
                        }

                        List<Uni<Prompt>> promptUnis = promptIds.stream()
                                .map(masterId ->
                                        promptService.getById(masterId, SuperUser.build())
                                                .flatMap(masterPrompt -> {
                                                    if (masterPrompt.getLanguageCode() == broadcastingLanguage) {
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
                                                Prompt selectedPrompt =
                                                        prompts.get(random.nextInt(prompts.size()));

                                                return draftFactory.createDraft(
                                                                song,
                                                                agent,
                                                                stream,
                                                                selectedPrompt.getDraftId(),
                                                                broadcastingLanguage,
                                                                userVariables
                                                        )
                                                        .map(draft -> new SongPromptDTO(
                                                                song.getId(),
                                                                draft,
                                                                selectedPrompt.getPrompt() + additionalInstruction,
                                                                selectedPrompt.getPromptType(),
                                                                agent.getLlmType(),
                                                                agent.getSearchEngineType(),
                                                                activeEntry.getScheduledStartTime().toLocalTime(),
                                                                true,
                                                                selectedPrompt.isPodcast()
                                                        ));
                                            })
                                            .toList();

                                    return Uni.join().all(songPromptUnis).andFailFast()
                                            .map(result -> Tuple2.of(result, currentSceneTitle));
                                });
                    });
        });
    }
}
