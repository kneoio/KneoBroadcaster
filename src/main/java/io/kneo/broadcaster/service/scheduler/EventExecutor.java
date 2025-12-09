package io.kneo.broadcaster.service.scheduler;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import io.kneo.broadcaster.agent.ElevenLabsClient;
import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.dto.queue.AddToQueueDTO;
import io.kneo.broadcaster.model.Action;
import io.kneo.broadcaster.model.Event;
import io.kneo.broadcaster.model.Prompt;
import io.kneo.broadcaster.model.StagePlaylist;
import io.kneo.broadcaster.model.cnst.ActionType;
import io.kneo.broadcaster.model.cnst.EventType;
import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.model.cnst.WayOfSourcing;
import io.kneo.broadcaster.model.radiostation.RadioStation;
import io.kneo.broadcaster.model.soundfragment.SoundFragment;
import io.kneo.broadcaster.service.AiAgentService;
import io.kneo.broadcaster.service.PromptService;
import io.kneo.broadcaster.service.QueueService;
import io.kneo.broadcaster.service.RadioStationService;
import io.kneo.broadcaster.service.manipulation.mixing.MergingType;
import io.kneo.broadcaster.service.live.DraftFactory;
import io.kneo.broadcaster.service.soundfragment.SoundFragmentService;
import io.kneo.broadcaster.util.AiHelperUtils;
import io.kneo.broadcaster.util.Randomizator;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.SuperUser;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class EventExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger(EventExecutor.class);
    private static final int AD_PRIORITY = 9;

    @Inject
    SoundFragmentService soundFragmentService;

    @Inject
    PromptService promptService;

    @Inject
    RadioStationService radioStationService;

    @Inject
    QueueService queueService;

    @Inject
    ElevenLabsClient elevenLabsClient;

    @Inject
    AiAgentService aiAgentService;

    @Inject
    DraftFactory draftFactory;

    @Inject
    BroadcasterConfig config;

    private AnthropicClient anthropicClient;

    private AnthropicClient getAnthropicClient() {
        if (anthropicClient == null) {
            anthropicClient = AnthropicOkHttpClient.builder()
                    .apiKey(config.getAnthropicApiKey())
                    .build();
        }
        return anthropicClient;
    }

    public Uni<Void> execute(Event event) {
        LOGGER.info("Executing event: {} ({})", event.getDescription(), event.getId());

        if (event.getType() == EventType.ADVERTISEMENT) {
            return executeAdvertisement(event);
        }

        List<Action> actions = event.getActions();
        if (actions == null || actions.isEmpty()) {
            LOGGER.debug("No actions for event: {}", event.getId());
            return Uni.createFrom().voidItem();
        }

        return executeActions(event, actions.stream().filter(Action::isActive).toList());
    }

    private Uni<Void> executeAdvertisement(Event event) {
        LOGGER.info("Executing ADVERTISEMENT event: {}", event.getId());

        StagePlaylist playlist = event.getStagePlaylist();
        if (playlist == null || playlist.getSourcing() != WayOfSourcing.RANDOM) {
            LOGGER.warn("Advertisement event {} has no RANDOM sourcing, skipping", event.getId());
            return Uni.createFrom().voidItem();
        }

        UUID brandId = event.getBrandId();

        return radioStationService.getById(brandId, SuperUser.build())
                .chain(station -> soundFragmentService.getByTypeAndBrand(PlaylistItemType.ADVERTISEMENT, brandId)
                        .chain(adFragments -> {
                            if (adFragments.isEmpty()) {
                                LOGGER.warn("No advertisement fragments found for brand: {}", station.getSlugName());
                                return Uni.createFrom().voidItem();
                            }

                            SoundFragment adFragment = Randomizator.pickRandom(adFragments);
                            LOGGER.info("Selected ad fragment: {} - {}", adFragment.getTitle(), adFragment.getId());

                            List<Action> activeActions = event.getActions().stream()
                                    .filter(Action::isActive)
                                    .filter(a -> a.getActionType() == ActionType.RUN_PROMPT)
                                    .toList();

                            if (activeActions.isEmpty()) {
                                LOGGER.info("No RUN_PROMPT actions, queuing ad without TTS");
                                return queueAdWithoutTts(station, adFragment);
                            }

                            Action selectedAction = Randomizator.pickRandom(activeActions);
                            return executeAdWithPrompt(station, adFragment, selectedAction);
                        }));
    }

    private Uni<Void> executeAdWithPrompt(RadioStation station, SoundFragment adFragment, Action action) {
        UUID promptId = action.getPromptId();

        return promptService.getById(promptId, SuperUser.build())
                .chain(prompt -> aiAgentService.getById(station.getAiAgentId(), SuperUser.build(), LanguageCode.en)
                        .chain(agent -> {
                            LanguageCode broadcastingLanguage = AiHelperUtils.selectLanguageByWeight(agent);
                            return draftFactory.createDraft(adFragment, agent, station, prompt.getDraftId(), broadcastingLanguage)
                                    .chain(draft -> generateAdText(prompt, draft))
                                    .chain(ttsText -> {
                                        String voiceId = AiHelperUtils.resolvePrimaryVoiceId(station, agent);
                                        return generateTtsAndQueue(station, adFragment, ttsText, voiceId);
                                    });
                        }));
    }

    private Uni<String> generateAdText(Prompt prompt, String draft) {
        return Uni.createFrom().item(() -> {
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(Model.CLAUDE_3_5_HAIKU_LATEST)
                    .maxTokens(256)
                    .system(prompt.getPrompt())
                    .addUserMessage(draft)
                    .build();

            Message response = getAnthropicClient().messages().create(params);

            String text = response.content().stream()
                    .filter(ContentBlock::isText)
                    .map(block -> block.asText().text())
                    .findFirst()
                    .orElseThrow();
            LOGGER.info("Generated ad text: {}", text);
            return text;
        });
    }

    private Uni<Void> generateTtsAndQueue(RadioStation station, SoundFragment adFragment, String ttsText, String voiceId) {
        String uploadId = UUID.randomUUID().toString();

        return elevenLabsClient.textToSpeech(ttsText, voiceId, config.getElevenLabsModelId())
                .chain(audioBytes -> {
                    try {
                        Path uploadsDir = Paths.get(config.getPathForExternalServiceUploads());
                        Files.createDirectories(uploadsDir);

                        String fileName = "ad_tts_" + uploadId + ".mp3";
                        Path audioFilePath = uploadsDir.resolve(fileName);
                        Files.write(audioFilePath, audioBytes);

                        LOGGER.info("TTS audio saved: {}", audioFilePath);

                        Map<String, String> filePaths = new HashMap<>();
                        filePaths.put("audio1", audioFilePath.toAbsolutePath().toString());

                        Map<String, UUID> soundFragments = new HashMap<>();
                        soundFragments.put("song1", adFragment.getId());

                        AddToQueueDTO dto = new AddToQueueDTO();
                        dto.setMergingMethod(MergingType.INTRO_SONG);
                        dto.setFilePaths(filePaths);
                        dto.setSoundFragments(soundFragments);
                        dto.setPriority(AD_PRIORITY);

                        LOGGER.info("Queuing advertisement for station: {}, fragment: {}, priority: {}",
                                station.getSlugName(), adFragment.getTitle(), AD_PRIORITY);

                        return queueService.addToQueue(station.getSlugName(), dto, uploadId);
                    } catch (IOException e) {
                        LOGGER.error("Failed to save TTS audio", e);
                        return Uni.createFrom().failure(e);
                    }
                })
                .onItem().invoke(result -> LOGGER.info("Advertisement queued successfully for station: {}", station.getSlugName()))
                .onFailure().invoke(err -> LOGGER.error("Failed to queue advertisement for station: {}", station.getSlugName(), err))
                .replaceWithVoid();
    }

    private Uni<Void> queueAdWithoutTts(RadioStation station, SoundFragment adFragment) {
        String uploadId = UUID.randomUUID().toString();

        Map<String, UUID> soundFragments = new HashMap<>();
        soundFragments.put("song1", adFragment.getId());

        AddToQueueDTO dto = new AddToQueueDTO();
        dto.setMergingMethod(MergingType.NOT_MIXED);
        dto.setSoundFragments(soundFragments);
        dto.setPriority(AD_PRIORITY);

        LOGGER.info("Queuing advertisement (no TTS) for station: {}, fragment: {}", station.getSlugName(), adFragment.getTitle());

        return queueService.addToQueue(station.getSlugName(), dto, uploadId)
                .onItem().invoke(result -> LOGGER.info("Advertisement queued (no TTS) for station: {}", station.getSlugName()))
                .replaceWithVoid();
    }

    private Uni<Void> executeActions(Event event, List<Action> actions) {
        if (actions.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        Uni<Void> chain = Uni.createFrom().voidItem();

        for (Action action : actions) {
            chain = chain.chain(() -> executeAction(event, action));
        }

        return chain;
    }

    private Uni<Void> executeAction(Event event, Action action) {
        ActionType type = action.getActionType();
        LOGGER.info("Executing action {} for event {}", type, event.getId());

        if (type == ActionType.RUN_PROMPT) {
            return executeRunPrompt(event, action);
        }
        if (type == ActionType.COMMAND_STOP_STREAM) {
            return executeStopStream(event, action);
        }

        LOGGER.warn("Unknown action type: {} for event: {}", type, event.getId());
        return Uni.createFrom().voidItem();
    }

    private Uni<Void> executeRunPrompt(Event event, Action action) {
        LOGGER.info("RUN_PROMPT action triggered for event: {}, promptId: {}", event.getId(), action.getPromptId());
        return Uni.createFrom().voidItem();
    }

    private Uni<Void> executeStopStream(Event event, Action action) {
        LOGGER.info("COMMAND_STOP_STREAM action triggered for event: {}", event.getId());
        return Uni.createFrom().voidItem();
    }
}
