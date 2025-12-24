package io.kneo.broadcaster.service.live;

import io.kneo.broadcaster.dto.aihelper.SongPromptDTO;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.dto.dashboard.AiDjStatsDTO;
import io.kneo.broadcaster.model.Action;
import io.kneo.broadcaster.model.Prompt;
import io.kneo.broadcaster.model.aiagent.AiAgent;
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

    @FunctionalInterface
    public interface MessageSink {
        void add(String stationSlug, AiDjStatsDTO.MessageType type, String message);
    }

    private final PromptService promptService;
    private final DraftFactory draftFactory;
    private final SceneService sceneService;
    private final SongSupplier songSupplier;
    private final SoundFragmentService soundFragmentService;

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
        SceneScheduleEntry activeEntry = stream.findActiveSceneEntry();

        if (activeEntry == null) {
            UUID prevSceneId = stream.getCurrentSceneId();
            if (prevSceneId != null) {
                SceneScheduleEntry prev = findSceneById(stream, prevSceneId);
                if (prev != null && prev.getActualEndTime() == null) {
                    prev.setActualEndTime(LocalDateTime.now());
                }
            }

            if (stream.isCompleted()) {
                stream.setStatus(RadioStationStatus.OFF_LINE);
                messageSink.add(
                        stream.getSlugName(),
                        AiDjStatsDTO.MessageType.INFO,
                        "Stream completed - all scenes played"
                );
            }
            return Uni.createFrom().item(() -> null);
        }

        UUID activeSceneId = activeEntry.getSceneId();
        UUID currentSceneId = stream.getCurrentSceneId();

        if (currentSceneId != null && !currentSceneId.equals(activeSceneId)) {
            SceneScheduleEntry prev = findSceneById(stream, currentSceneId);
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
        List<ScheduledSongEntry> scheduledSongs = activeEntry.getSongs();

        if (!scheduledSongs.isEmpty()) {
            List<ScheduledSongEntry> availableEntries = scheduledSongs.stream()
                    .filter(e -> !fetchedSongsInScene.contains(e.getSoundFragment().getId()))
                    .toList();

            if (availableEntries.isEmpty()) {
                activeEntry.setActualEndTime(LocalDateTime.now());
                stream.clearSceneState(activeSceneId);
                return Uni.createFrom().item(() -> null);
            }

            int take = availableEntries.size() >= 2 && new Random().nextDouble() < 0.7 ? 2 : 1;
            songsUni = Uni.createFrom().item(
                    availableEntries.stream()
                            .limit(take)
                            .map(ScheduledSongEntry::getSoundFragment)
                            .toList()
            );
        } else {
            songsUni = getSongsFromEntry(
                    activeEntry,
                    stream.getMasterBrand().getSlugName(),
                    stream.getMasterBrand().getId(),
                    songSupplier,
                    soundFragmentService
            );
        }

        return songsUni.flatMap(songs -> {
            if (songs.isEmpty()) {
                return Uni.createFrom().item(() -> null);
            }

            return sceneService.getById(activeEntry.getSceneId(), SuperUser.build())
                    .chain(scene -> {
                        List<UUID> promptIds = scene.getPrompts() == null
                                ? List.of()
                                : scene.getPrompts().stream()
                                .filter(Action::isActive)
                                .map(Action::getPromptId)
                                .toList();

                        if (promptIds.isEmpty()) {
                            return Uni.createFrom().item(() -> null);
                        }

                        List<Uni<Prompt>> promptUnis = promptIds.stream()
                                .map(id ->
                                        promptService.getById(id, SuperUser.build())
                                                .flatMap(master -> {
                                                    if (master.getLanguageCode() == broadcastingLanguage) {
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
                                                return draftFactory.createDraft(
                                                                song,
                                                                agent,
                                                                stream,
                                                                selected.getDraftId(),
                                                                broadcastingLanguage,
                                                                userVariables
                                                        )
                                                        .map(draft -> new SongPromptDTO(
                                                                song.getId(),
                                                                draft,
                                                                selected.getPrompt() + additionalInstruction,
                                                                selected.getPromptType(),
                                                                agent.getLlmType(),
                                                                agent.getSearchEngineType(),
                                                                activeEntry.getScheduledStartTime().toLocalTime(),
                                                                true,
                                                                selected.isPodcast()
                                                        ));
                                            })
                                            .toList();

                                    return Uni.join().all(songPromptUnis).andFailFast()
                                            .map(result -> {
                                                songs.forEach(s -> fetchedSongsInScene.add(s.getId()));
                                                return Tuple2.of(result, currentSceneTitle);
                                            });
                                });
                    });
        });
    }

    private SceneScheduleEntry findSceneById(OneTimeStream stream, UUID sceneId) {
        if (stream.getStreamSchedule() == null) {
            return null;
        }
        return stream.getStreamSchedule().getSceneScheduleEntries().stream()
                .filter(s -> s.getSceneId().equals(sceneId))
                .findFirst()
                .orElse(null);
    }

    public int getFetchedSongsCount(OneTimeStream stream, UUID sceneId) {
        return stream.getFetchedSongsInScene(sceneId).size();
    }
}
