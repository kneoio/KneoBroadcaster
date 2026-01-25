package io.kneo.broadcaster.service.live;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import io.kneo.broadcaster.agent.ElevenLabsClient;
import io.kneo.broadcaster.agent.ModelslabClient;
import io.kneo.broadcaster.agent.TextToSpeechClient;
import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.dto.SoundFragmentDTO;
import io.kneo.broadcaster.model.Prompt;
import io.kneo.broadcaster.model.aiagent.AiAgent;
import io.kneo.broadcaster.model.aiagent.TTSEngineType;
import io.kneo.broadcaster.model.aiagent.Voice;
import io.kneo.broadcaster.model.cnst.GeneratedContentStatus;
import io.kneo.broadcaster.model.cnst.LanguageTag;
import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.model.cnst.SourceType;
import io.kneo.broadcaster.model.soundfragment.SoundFragment;
import io.kneo.broadcaster.model.stream.IStream;
import io.kneo.broadcaster.model.stream.LiveScene;
import io.kneo.broadcaster.model.stream.PendingSongEntry;
import io.kneo.broadcaster.repository.soundfragment.SoundFragmentRepository;
import io.kneo.broadcaster.service.AiAgentService;
import io.kneo.broadcaster.service.PromptService;
import io.kneo.broadcaster.service.exceptions.AudioMergeException;
import io.kneo.broadcaster.service.manipulation.FFmpegProvider;
import io.kneo.broadcaster.service.manipulation.mixing.AudioConcatenator;
import io.kneo.broadcaster.service.manipulation.mixing.handler.AudioMixingHandler;
import io.kneo.broadcaster.service.soundfragment.SoundFragmentService;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.SuperUser;
import io.kneo.core.util.WebHelper;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class GeneratedNewsService {
    private static final Logger LOGGER = LoggerFactory.getLogger(GeneratedNewsService.class);

    private final PromptService promptService;
    private final SoundFragmentService soundFragmentService;
    private final ElevenLabsClient elevenLabsClient;
    private final ModelslabClient modelslabClient;
    private final BroadcasterConfig config;
    private final AnthropicClient anthropicClient;
    private final DraftFactory draftFactory;
    private final AiAgentService aiAgentService;
    private final SoundFragmentRepository soundFragmentRepository;
    private final FFmpegProvider ffmpegProvider;
    private final AudioConcatenator audioConcatenator;

    @Inject
    public GeneratedNewsService(
            PromptService promptService,
            SoundFragmentService soundFragmentService,
            ElevenLabsClient elevenLabsClient,
            ModelslabClient modelslabClient,
            BroadcasterConfig config,
            DraftFactory draftFactory,
            AiAgentService aiAgentService,
            SoundFragmentRepository soundFragmentRepository,
            FFmpegProvider ffmpegProvider,
            AudioConcatenator audioConcatenator
    ) {
        this.promptService = promptService;
        this.soundFragmentService = soundFragmentService;
        this.elevenLabsClient = elevenLabsClient;
        this.modelslabClient = modelslabClient;
        this.config = config;
        this.draftFactory = draftFactory;
        this.aiAgentService = aiAgentService;
        this.soundFragmentRepository = soundFragmentRepository;
        this.ffmpegProvider = ffmpegProvider;
        this.audioConcatenator = audioConcatenator;
        this.anthropicClient = AnthropicOkHttpClient.builder()
                .apiKey(config.getAnthropicApiKey())
                .timeout(java.time.Duration.ofSeconds(60))
                .build();
    }

    public Uni<SoundFragment> generateNewsFragment(
            UUID promptId,
            AiAgent agent,
            IStream stream,
            UUID brandId,
            LiveScene activeEntry,
            LanguageTag broadcastingLanguage
    ) {
        return promptService.getById(promptId, SuperUser.build())
                .flatMap(masterPrompt -> {
                    if (masterPrompt.getLanguageTag() == broadcastingLanguage) {
                        return Uni.createFrom().item(masterPrompt);
                    }
                    return promptService
                            .findByMasterAndLanguage(promptId, broadcastingLanguage, false)
                            .map(p -> p != null ? p : masterPrompt);
                })
                .chain(prompt -> generateText(prompt, agent, stream, broadcastingLanguage)
                        .chain(text -> {
                            if (text == null) {
                                return Uni.createFrom().failure(new RuntimeException("Generated content contains technical difficulty/error - skipping generation"));
                            }
                            return generateTtsAndSave(text, prompt, agent, brandId, activeEntry);
                        })
                );
    }

    private Uni<String> generateText(Prompt prompt, AiAgent agent, IStream stream, LanguageTag broadcastingLanguage) {
        return draftFactory.createDraft(
                null,
                agent,
                stream,
                prompt.getDraftId(),
                LanguageTag.EN_US,  //for now, we use en for draft explicitly
                new HashMap<>()
        ).chain(draftContent -> Uni.createFrom().item(() -> {
            LOGGER.info("Draft content received: {}", draftContent);

            // Check if draft contains error
            if (draftContent.contains("\"error\":") || draftContent.contains("Search failed")) {
                LOGGER.error("Draft content contains error, skipping generation: {}", draftContent);
                return null;
            }

            String fullPrompt = String.format(
                    "%s\n\nDraft input:\n%s",
                    prompt.getPrompt(),
                    draftContent
            );

            LOGGER.info("Sending prompt to Claude (length: {} chars)", fullPrompt.length());

            long maxTokens = 2048L;
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(Model.CLAUDE_HAIKU_4_5_20251001)
                    .maxTokens(maxTokens)
                    .system("You are a professional radio news presenter")
                    .addUserMessage(fullPrompt)
                    .build();

            try {
                Message response = anthropicClient.messages().create(params);

                LOGGER.info("Claude response received - Input tokens: {}, Output tokens: {}",
                        response.usage().inputTokens(), response.usage().outputTokens());

                String text = response.content().stream()
                        .filter(ContentBlock::isText)
                        .map(block -> block.asText().text())
                        .findFirst()
                        .orElseThrow(() -> new RuntimeException("No text generated from AI"));

                if (response.usage().outputTokens() >= maxTokens * 0.95) {
                    LOGGER.warn("News generation used {} tokens ({}% of max {}). Response may be truncated.",
                            response.usage().outputTokens(),
                            Math.round((response.usage().outputTokens() / (double) maxTokens) * 100),
                            maxTokens);
                }
                if (text.contains("technical difficulty")
                        || text.contains("technical error")
                        || text.contains("technical issue")) {
                    return null;
                } else {
                    LOGGER.info("Generated news text ({} tokens): {}", response.usage().outputTokens(), text);
                    return text;
                }
            } catch (Exception e) {
                LOGGER.error("Anthropic API call failed - Type: {}, Message: {}", e.getClass().getSimpleName(), e.getMessage(), e);
                throw e;
            }
        }));
    }

    private Uni<SoundFragment> generateTtsAndSave(
            String text,
            Prompt prompt,
            AiAgent agent,
            UUID brandId,
            LiveScene activeEntry
    ) {
        String uploadId = UUID.randomUUID().toString();

        Voice voice;
        if (agent.getTtsSetting().getNewsReporter() != null) {
            voice = agent.getTtsSetting().getNewsReporter();
        } else {
            throw new RuntimeException("News reporter voice not configured");
        }

        return Uni.createFrom().item(voice).chain(v -> {
            LOGGER.info("Starting TTS generation for scene '{}' using voice: {} (engine: {})",
                    activeEntry.getSceneTitle(), voice.getId(), voice.getEngineType());

            TextToSpeechClient ttsClient;
            String modelId;
            String actualVoiceId;

            if (voice.getEngineType() == TTSEngineType.MODELSLAB) {
                ttsClient = modelslabClient;
                modelId = null;
                LOGGER.info("Using Modelslab TTS client");
            } else {
                ttsClient = elevenLabsClient;
                modelId = config.getElevenLabsModelId();
                LOGGER.info("Using ElevenLabs TTS client with model: {}", modelId);
            }
            actualVoiceId = voice.getId();

            LOGGER.info("Calling TTS API with text length: {} characters", text.length());
            return ttsClient.textToSpeech(text, actualVoiceId, modelId, agent.getPreferredLang().getFirst().getLanguageTag())
                    .chain(audioBytes -> {
                        LOGGER.info("TTS generation successful! Received {} bytes of audio data", audioBytes.length);
                        try {
                            Path uploadsDir = Paths.get(config.getPathUploads(), "generated-news-service", "supervisor", "temp");
                            Files.createDirectories(uploadsDir);

                            String fileName = "generated_news_" + uploadId + ".mp3";
                            Path ttsFilePath = uploadsDir.resolve(fileName);
                            Files.write(ttsFilePath, audioBytes);

                            LOGGER.info("Generated news TTS saved: {}", ttsFilePath);

                            AudioMixingHandler mixingHandler = new AudioMixingHandler(
                                    config,
                                    soundFragmentRepository,
                                    soundFragmentService,
                                    audioConcatenator,
                                    aiAgentService,
                                    ffmpegProvider
                            );

                            String mixedFileName = "mixed_news_" + uploadId + ".wav";
                            Path mixedFilePath = uploadsDir.resolve(mixedFileName);

                            return mixingHandler.mixNewsWithBackgroundAndIntros(
                                    ttsFilePath.toString(),
                                    mixedFilePath.toString(),
                                    0.40
                            ).chain(mixedPath -> {
                                LOGGER.info("News mixed with background and jingles: {}", mixedPath);
                                return createAndSaveSoundFragment(
                                        Path.of(mixedPath),
                                        prompt,
                                        brandId,
                                        activeEntry,
                                        text
                                );
                            });
                        } catch (IOException | AudioMergeException e) {
                            LOGGER.error("Failed to save or mix TTS audio for scene '{}'", activeEntry.getSceneTitle(), e);
                            return Uni.createFrom().failure(e);
                        }
                    })
                    .onFailure().recoverWithUni(error -> {
                        LOGGER.error("TTS generation failed for scene '{}' - Error: {}",
                                activeEntry.getSceneTitle(), error.getMessage(), error);
                        activeEntry.setGeneratedContentStatus(GeneratedContentStatus.ERROR);
                        return Uni.createFrom().failure(error);
                    });
        });
    }

    private Uni<SoundFragment> createAndSaveSoundFragment(
            Path audioFilePath,
            Prompt prompt,
            UUID brandId,
            LiveScene activeEntry,
            String text
    ) {
        try {
            Path targetDir = Paths.get(config.getPathUploads(), "sound-fragments-controller", "supervisor", "temp");
            Files.createDirectories(targetDir);

            Path targetPath = targetDir.resolve(audioFilePath.getFileName());
            Files.copy(audioFilePath, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            SoundFragmentDTO dto = new SoundFragmentDTO();
            dto.setType(PlaylistItemType.NEWS);
            String currentDate = LocalDate.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy"));
            dto.setTitle(prompt.getTitle() + " " + currentDate);
            dto.setArtist("AI Generated");
            dto.setGenres(List.of());
            dto.setLabels(List.of());
            dto.setSource(SourceType.TEMPORARY_MIX);
            dto.setExpiresAt(LocalDate.now().plusDays(1).atStartOfDay());
            dto.setLength(Duration.ofSeconds(30));
            // Include the generated text in description for debugging
            String truncatedText = text.length() > 200 ? text.substring(0, 200) + "..." : text;
            dto.setDescription("AI generated news content " + currentDate + "\n\nContent: " + truncatedText);
            dto.setRepresentedInBrands(List.of(brandId));
            dto.setNewlyUploaded(List.of(audioFilePath.getFileName().toString()));

            String slugName = WebHelper.generateSlug(dto.getArtist(), dto.getTitle());
            dto.setSlugName(slugName);

            return soundFragmentService.upsert("new", dto, SuperUser.build(), LanguageCode.en)
                    .map(savedDto -> {
                        SoundFragment fragment = new SoundFragment();
                        fragment.setId(savedDto.getId());
                        fragment.setType(savedDto.getType());
                        fragment.setTitle(savedDto.getTitle());
                        fragment.setArtist(savedDto.getArtist());
                        fragment.setGenres(savedDto.getGenres());
                        fragment.setLabels(savedDto.getLabels());
                        fragment.setSource(savedDto.getSource());
                        fragment.setExpiresAt(savedDto.getExpiresAt());
                        fragment.setLength(savedDto.getLength());
                        fragment.setDescription(savedDto.getDescription());
                        fragment.setSlugName(savedDto.getSlugName());

                        PendingSongEntry entry = new PendingSongEntry(fragment, activeEntry.getScheduledStartTime());
                        activeEntry.addSong(entry);
                        activeEntry.setGeneratedContentTimestamp(LocalDateTime.now());
                        activeEntry.setGeneratedFragmentId(fragment.getId());
                        activeEntry.setGeneratedContentStatus(GeneratedContentStatus.GENERATED);

                        LOGGER.info("Generated news fragment saved and added to scene: {}", fragment.getId());
                        return fragment;
                    });
        } catch (IOException e) {
            LOGGER.error("Failed to copy audio file to target directory", e);
            return Uni.createFrom().failure(e);
        }
    }
}
