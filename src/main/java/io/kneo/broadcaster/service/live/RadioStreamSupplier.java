package io.kneo.broadcaster.service.live;

import io.kneo.broadcaster.dto.aihelper.SongPromptDTO;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.dto.dashboard.AiDjStatsDTO;
import io.kneo.broadcaster.model.Action;
import io.kneo.broadcaster.model.Prompt;
import io.kneo.broadcaster.model.aiagent.AiAgent;
import io.kneo.broadcaster.model.soundfragment.SoundFragment;
import io.kneo.broadcaster.model.stream.RadioStream;
import io.kneo.broadcaster.model.stream.SceneScheduleEntry;
import io.kneo.broadcaster.model.stream.ScheduledSongEntry;
import io.kneo.broadcaster.service.PromptService;
import io.kneo.broadcaster.service.SceneService;
import io.kneo.core.localization.LanguageCode;
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
public class RadioStreamSupplier {

    @FunctionalInterface
    public interface MessageSink {
        void add(String stationSlug, AiDjStatsDTO.MessageType type, String message);
    }

    private final PromptService promptService;
    private final DraftFactory draftFactory;
    private final SceneService sceneService;

    @Inject
    public RadioStreamSupplier(PromptService promptService, DraftFactory draftFactory, SceneService sceneService) {
        this.promptService = promptService;
        this.draftFactory = draftFactory;
        this.sceneService = sceneService;
    }

    public Uni<Tuple2<List<SongPromptDTO>, String>> fetchPromptForRadioStream(
            RadioStream stream,
            AiAgent agent,
            LanguageCode broadcastingLanguage,
            String additionalInstruction,
            MessageSink messageSink
    ) {
        SceneScheduleEntry activeEntry = stream.findActiveSceneEntry();

        if (activeEntry == null) {
            stream.setStatus(RadioStationStatus.OFF_LINE);
            messageSink.add(stream.getSlugName(), AiDjStatsDTO.MessageType.INFO, "No active scene - schedule may need refresh");
            return Uni.createFrom().item(() -> null);
        }

        String currentSceneTitle = activeEntry.getSceneTitle();

        List<SoundFragment> songs = activeEntry.getSongs().stream()
                .filter(s -> !s.isPlayed())
                .limit(1)
                .peek(ScheduledSongEntry::markAsPlayed)
                .map(ScheduledSongEntry::getSoundFragment)
                .toList();

        if (songs.isEmpty()) {
            messageSink.add(
                    stream.getSlugName(),
                    AiDjStatsDTO.MessageType.INFO,
                    String.format("No more scheduled songs for scene '%s'", currentSceneTitle)
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
                                stream.getSlugName(),
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
                                            Prompt selectedPrompt = prompts.get(random.nextInt(prompts.size()));
                                            return draftFactory.createDraft(
                                                            song,
                                                            agent,
                                                            stream,
                                                            selectedPrompt.getDraftId(),
                                                            broadcastingLanguage,
                                                            Map.of()
                                                    )
                                                    .map(draft -> new SongPromptDTO(
                                                            song.getId(),
                                                            draft,
                                                            selectedPrompt.getPrompt() + additionalInstruction,
                                                            selectedPrompt.getPromptType(),
                                                            agent.getLlmType(),
                                                            agent.getSearchEngineType(),
                                                            activeEntry.getScheduledStartTime().toLocalTime(),
                                                            false,
                                                            selectedPrompt.isPodcast()
                                                    ));
                                        })
                                        .toList();

                                return Uni.join().all(songPromptUnis).andFailFast()
                                        .map(result -> Tuple2.of(result, currentSceneTitle));
                            });
                });
    }
}
