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
import io.kneo.broadcaster.model.Event;
import io.kneo.broadcaster.model.LivePrompt;
import io.kneo.broadcaster.model.PlaylistRequest;
import io.kneo.broadcaster.model.Prompt;
import io.kneo.broadcaster.model.cnst.ActionType;
import io.kneo.broadcaster.model.cnst.EventType;
import io.kneo.broadcaster.model.cnst.LanguageTag;
import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.model.cnst.StreamStatus;
import io.kneo.broadcaster.model.cnst.WayOfSourcing;
import io.kneo.broadcaster.model.soundfragment.SoundFragment;
import io.kneo.broadcaster.model.stream.IStream;
import io.kneo.broadcaster.service.AiAgentService;
import io.kneo.broadcaster.service.BrandService;
import io.kneo.broadcaster.service.PromptService;
import io.kneo.broadcaster.service.QueueService;
import io.kneo.broadcaster.service.live.DraftFactory;
import io.kneo.broadcaster.service.manipulation.mixing.MergingType;
import io.kneo.broadcaster.service.playlist.SongSupplier;
import io.kneo.broadcaster.service.soundfragment.SoundFragmentService;
import io.kneo.broadcaster.service.stream.RadioStationPool;
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
    private static final int EVENT_PRIORITY = 9;

    @Inject
    SoundFragmentService soundFragmentService;

    @Inject
    PromptService promptService;

    @Inject
    BrandService brandService;

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

    @Inject
    RadioStationPool radioStationPool;

    @Inject
    SongSupplier songSupplier;

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


        List<LivePrompt> livePrompts = event.getLivePrompts();
        if (livePrompts == null || livePrompts.isEmpty()) {
            LOGGER.debug("No actions for event: {}", event.getId());
            return Uni.createFrom().voidItem();
        }

        return executeActions(event, livePrompts.stream().filter(LivePrompt::isActive).toList());
    }

    private Uni<Void> executeScheduledEvent(Event event, PlaylistItemType fragmentType) {
        LOGGER.info("Executing {} event: {}", event.getType(), event.getId());

        UUID brandId = event.getBrandId();

        return brandService.getById(brandId, SuperUser.build())
                .chain(station -> {
                    IStream stationStream = radioStationPool.getStation(station.getSlugName());
                    if (stationStream == null) {
                        LOGGER.info("Station {} is offline, skipping event {}", station.getSlugName(), event.getId());
                        return Uni.createFrom().voidItem();
                    }
                    StreamStatus status = stationStream.getStatus();
                    if (status != StreamStatus.WARMING_UP &&
                        status != StreamStatus.ON_LINE &&
                        status != StreamStatus.QUEUE_SATURATED) {
                        LOGGER.info("Station {} has status {}, skipping event {}", station.getSlugName(), status, event.getId());
                        return Uni.createFrom().voidItem();
                    }
                    return fetchFragmentsForEvent(stationStream, event, fragmentType)
                        .chain(fragments -> {
                            if (fragments.isEmpty()) {
                                LOGGER.warn("No {} fragments found for event: {}", fragmentType, event.getId());
                                return Uni.createFrom().voidItem();
                            }

                            SoundFragment fragment = Randomizator.pickRandom(fragments);
                            LOGGER.info("Selected {} fragment: {} - {}", fragmentType, fragment.getTitle(), fragment.getId());

                            List<LivePrompt> activeLivePrompts = event.getLivePrompts().stream()
                                    .filter(LivePrompt::isActive)
                                    .filter(a -> a.getActionType() == ActionType.QUEUE_UP)
                                    .toList();

                            if (activeLivePrompts.isEmpty()) {
                                LOGGER.info("No RUN_PROMPT actions, queuing fragment without TTS");
                                return queueFragmentWithoutTts(stationStream, fragment);
                            }

                            LivePrompt selectedLivePrompt = Randomizator.pickRandom(activeLivePrompts);
                            return executeWithPrompt(stationStream, fragment, selectedLivePrompt);
                        });
                });
    }

    private Uni<List<SoundFragment>> fetchFragmentsForEvent(IStream stationStream, Event event, PlaylistItemType fragmentType) {
        PlaylistRequest playlistRequest = event.getPlaylistRequest();

        if (playlistRequest == null) {
            return soundFragmentService.getByTypeAndBrand(fragmentType, event.getBrandId());
        }

        WayOfSourcing sourcing = playlistRequest.getSourcing();

        if (sourcing == WayOfSourcing.RANDOM) {
            return soundFragmentService.getByTypeAndBrand(fragmentType, event.getBrandId());
        }

        if (sourcing == WayOfSourcing.QUERY) {
            return songSupplier.getNextSongByQuery(stationStream.getId(), playlistRequest, 10);
        }

        if (sourcing == WayOfSourcing.STATIC_LIST) {
            return songSupplier.getNextSongFromStaticList(playlistRequest.getSoundFragments(), 10);
        }

        throw new IllegalStateException("Unknown sourcing type: " + sourcing);
    }

    private Uni<Void> executeWithPrompt(IStream stationStream, SoundFragment fragment, LivePrompt livePrompt) {
        UUID promptId = livePrompt.getPromptId();

        return promptService.getById(promptId, SuperUser.build())
                .chain(prompt -> aiAgentService.getById(stationStream.getAiAgentId(), SuperUser.build(), LanguageCode.en)
                        .chain(agent -> {
                            return draftFactory.createDraft(fragment, agent, stationStream, prompt.getDraftId(), LanguageTag.EN_US, null)
                                    .chain(draft -> generateText(prompt, draft))
                                    .chain(ttsText -> {
                                        String voiceId = AiHelperUtils.resolvePrimaryVoiceId(stationStream, agent);
                                        return generateTtsAndQueue(stationStream, fragment, ttsText, voiceId);
                                    });
                        }));
    }

    private Uni<String> generateText(Prompt prompt, String draft) {
        return Uni.createFrom().item(() -> {
            String userMessage = prompt.getPrompt() + "\n\nDraft input:\n" + draft;

            MessageCreateParams params = MessageCreateParams.builder()
                    .model(Model.CLAUDE_HAIKU_4_5_20251001)
                    .maxTokens(256)
                    .addUserMessage(userMessage)
                    .build();

            Message response = getAnthropicClient().messages().create(params);

            String text = response.content().stream()
                    .filter(ContentBlock::isText)
                    .map(block -> block.asText().text())
                    .findFirst()
                    .orElseThrow();
            LOGGER.info("Generated text: {}", text);
            return text;
        });
    }

    private Uni<Void> generateTtsAndQueue(IStream station, SoundFragment fragment, String ttsText, String voiceId) {
        String uploadId = UUID.randomUUID().toString();

        return elevenLabsClient.textToSpeech(ttsText, voiceId, config.getElevenLabsModelId())
                .chain(audioBytes -> {
                    try {
                        Path uploadsDir = Paths.get(config.getPathForExternalServiceUploads());
                        Files.createDirectories(uploadsDir);

                        String fileName = "event_tts_" + uploadId + ".mp3";
                        Path audioFilePath = uploadsDir.resolve(fileName);
                        Files.write(audioFilePath, audioBytes);

                        LOGGER.info("TTS audio saved: {}", audioFilePath);

                        Map<String, String> filePaths = new HashMap<>();
                        filePaths.put("audio1", audioFilePath.toAbsolutePath().toString());

                        Map<String, UUID> soundFragments = new HashMap<>();
                        soundFragments.put("song1", fragment.getId());

                        AddToQueueDTO dto = new AddToQueueDTO();
                        dto.setMergingMethod(MergingType.INTRO_SONG);
                        dto.setFilePaths(filePaths);
                        dto.setSoundFragments(soundFragments);
                        dto.setPriority(EVENT_PRIORITY);

                        LOGGER.info("Queuing event for station: {}, fragment: {}, priority: {}",
                                station.getSlugName(), fragment.getTitle(), EVENT_PRIORITY);

                        return queueService.addToQueue(station.getSlugName(), dto, uploadId);
                    } catch (IOException e) {
                        LOGGER.error("Failed to save TTS audio", e);
                        return Uni.createFrom().failure(e);
                    }
                })
                .onItem().invoke(result -> LOGGER.info("Event queued successfully for station: {}", station.getSlugName()))
                .onFailure().invoke(err -> LOGGER.error("Failed to queue event for station: {}", station.getSlugName(), err))
                .replaceWithVoid();
    }

    private Uni<Void> queueFragmentWithoutTts(IStream station, SoundFragment fragment) {
        String uploadId = UUID.randomUUID().toString();

        Map<String, UUID> soundFragments = new HashMap<>();
        soundFragments.put("song1", fragment.getId());

        AddToQueueDTO dto = new AddToQueueDTO();
        dto.setMergingMethod(MergingType.SONG_ONLY);
        dto.setSoundFragments(soundFragments);
        dto.setPriority(EVENT_PRIORITY);

        LOGGER.info("Queuing fragment (no TTS) for station: {}, fragment: {}", station.getSlugName(), fragment.getTitle());

        return queueService.addToQueue(station.getSlugName(), dto, uploadId)
                .onItem().invoke(result -> LOGGER.info("Fragment queued (no TTS) for station: {}", station.getSlugName()))
                .replaceWithVoid();
    }

    private Uni<Void> executeActions(Event event, List<LivePrompt> livePrompts) {
        if (livePrompts.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        Uni<Void> chain = Uni.createFrom().voidItem();

        for (LivePrompt livePrompt : livePrompts) {
            chain = chain.chain(() -> executeAction(event, livePrompt));
        }

        return chain;
    }

    private Uni<Void> executeAction(Event event, LivePrompt livePrompt) {
        ActionType type = livePrompt.getActionType();
        LOGGER.info("Executing action {} for event {}", type, event.getId());

        if (type == ActionType.QUEUE_UP) {
            if (event.getType() == EventType.ADVERTISEMENT) {
                return executeScheduledEvent(event, PlaylistItemType.ADVERTISEMENT);
            }
            if (event.getType() == EventType.REMINDER) {
                return executeScheduledEvent(event, PlaylistItemType.SONG);
            }
        }
        if (type == ActionType.COMMAND_STOP_STREAM) {
            return executeStopStream(event, livePrompt);
        }

        LOGGER.warn("Unknown action type: {} for event: {}", type, event.getId());
        return Uni.createFrom().voidItem();
    }

    private Uni<Void> executeStopStream(Event event, LivePrompt livePrompt) {
        LOGGER.info("COMMAND_STOP_STREAM action triggered for event: {}", event.getId());
        return Uni.createFrom().voidItem();
    }
}
