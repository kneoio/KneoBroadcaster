package io.kneo.broadcaster.service.live;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import io.kneo.broadcaster.agent.ElevenLabsClient;
import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.dto.SoundFragmentDTO;
import io.kneo.broadcaster.model.Prompt;
import io.kneo.broadcaster.model.aiagent.AiAgent;
import io.kneo.broadcaster.model.cnst.LanguageTag;
import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.model.cnst.SourceType;
import io.kneo.broadcaster.model.soundfragment.SoundFragment;
import io.kneo.broadcaster.model.stream.IStream;
import io.kneo.broadcaster.model.stream.LiveScene;
import io.kneo.broadcaster.model.stream.ScheduledSongEntry;
import io.kneo.broadcaster.repository.soundfragment.SoundFragmentRepository;
import io.kneo.broadcaster.service.PromptService;
import io.kneo.broadcaster.service.exceptions.AudioMergeException;
import io.kneo.broadcaster.service.manipulation.FFmpegProvider;
import io.kneo.broadcaster.service.manipulation.mixing.AudioConcatenator;
import io.kneo.broadcaster.service.manipulation.mixing.handler.AudioMixingHandler;
import io.kneo.broadcaster.service.soundfragment.SoundFragmentService;
import io.kneo.broadcaster.util.AiHelperUtils;
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
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class GeneratedNewsService {
    private static final Logger LOGGER = LoggerFactory.getLogger(GeneratedNewsService.class);

    private final PromptService promptService;
    private final SoundFragmentService soundFragmentService;
    private final ElevenLabsClient elevenLabsClient;
    private final BroadcasterConfig config;
    private final AnthropicClient anthropicClient;
    private final DraftFactory draftFactory;
    private final io.kneo.broadcaster.service.AiAgentService aiAgentService;
    private final SoundFragmentRepository soundFragmentRepository;
    private final FFmpegProvider ffmpegProvider;
    private final AudioConcatenator audioConcatenator;

    @Inject
    public GeneratedNewsService(
            PromptService promptService,
            SoundFragmentService soundFragmentService,
            ElevenLabsClient elevenLabsClient,
            BroadcasterConfig config,
            DraftFactory draftFactory,
            io.kneo.broadcaster.service.AiAgentService aiAgentService,
            SoundFragmentRepository soundFragmentRepository,
            FFmpegProvider ffmpegProvider,
            AudioConcatenator audioConcatenator
    ) {
        this.promptService = promptService;
        this.soundFragmentService = soundFragmentService;
        this.elevenLabsClient = elevenLabsClient;
        this.config = config;
        this.draftFactory = draftFactory;
        this.aiAgentService = aiAgentService;
        this.soundFragmentRepository = soundFragmentRepository;
        this.ffmpegProvider = ffmpegProvider;
        this.audioConcatenator = audioConcatenator;
        this.anthropicClient = AnthropicOkHttpClient.builder()
                .apiKey(config.getAnthropicApiKey())
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
                        .chain(text -> generateTtsAndSave(text, prompt, agent, stream, brandId, activeEntry))
                );
    }

    private Uni<String> generateText(Prompt prompt, AiAgent agent, IStream stream, LanguageTag broadcastingLanguage) {
        return draftFactory.createDraft(
                null,
                agent,
                stream,
                prompt.getDraftId(),
                broadcastingLanguage,
                new HashMap<>()
        ).chain(draftContent -> Uni.createFrom().item(() -> {
            String fullPrompt = String.format(
                    "%s\n\nDraft input:\n%s",
                    prompt.getPrompt(),
                    draftContent
            );

            long maxTokens = 2048L;
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(Model.CLAUDE_HAIKU_4_5_20251001)
                    .maxTokens(maxTokens)
                    .system("You are a professional radio news presenter")
                    .addUserMessage(fullPrompt)
                    .build();

            Message response = anthropicClient.messages().create(params);

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

            LOGGER.info("Generated news text ({} tokens): {}", response.usage().outputTokens(), text);
            return text;
        }));
    }

    private Uni<SoundFragment> generateTtsAndSave(
            String text,
            Prompt prompt,
            AiAgent agent,
            IStream stream,
            UUID brandId,
            LiveScene activeEntry
    ) {
        String uploadId = UUID.randomUUID().toString();
        
        Uni<String> voiceIdUni = agent.getNewsReporter() != null
                ? aiAgentService.getById(agent.getNewsReporter(), SuperUser.build(), LanguageCode.en)
                        .map(newsReporter -> newsReporter.getPrimaryVoice().stream()
                                .findFirst()
                                .orElseThrow(() -> new RuntimeException("No voice found for news reporter"))
                                .getId())
                : Uni.createFrom().item(AiHelperUtils.resolvePrimaryVoiceId(stream, agent));

        return voiceIdUni.chain(voiceId -> elevenLabsClient.textToSpeech(text, voiceId, config.getElevenLabsModelId())
                .chain(audioBytes -> {
                    try {
                        Path uploadsDir = Paths.get(config.getPathUploads(), "sound-fragments-controller", "supervisor", "temp");
                        Files.createDirectories(uploadsDir);

                        String fileName = "generated_news_tts_" + uploadId + ".mp3";
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
                                0.40   // if more it louder
                        ).chain(mixedPath -> {
                            LOGGER.info("News mixed with background and jingles: {}", mixedPath);
                            return createAndSaveSoundFragment(
                                    Path.of(mixedPath),
                                    prompt,
                                    brandId,
                                    activeEntry
                            );
                        });
                    } catch (IOException | AudioMergeException e) {
                        LOGGER.error("Failed to save or mix TTS audio", e);
                        return Uni.createFrom().failure(e);
                    }
                }));
    }

    private Uni<SoundFragment> createAndSaveSoundFragment(
            Path audioFilePath,
            Prompt prompt,
            UUID brandId,
            LiveScene activeEntry
    ) {
        SoundFragmentDTO dto = new SoundFragmentDTO();
        dto.setType(PlaylistItemType.NEWS);
        dto.setTitle(prompt.getTitle());
        dto.setArtist("AI Generated");
        dto.setGenres(List.of());
        dto.setLabels(List.of());
        dto.setSource(SourceType.TEMPORARY_MIX);
        dto.setExpiresAt(LocalDate.now().plusDays(1).atStartOfDay());
        dto.setLength(Duration.ofSeconds(30));
        dto.setDescription("AI generated news content");
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

                    ScheduledSongEntry entry = new ScheduledSongEntry(fragment, activeEntry.getScheduledStartTime());
                    activeEntry.addSong(entry);
                    activeEntry.setGeneratedContentTimestamp(LocalDateTime.now());
                    activeEntry.setGeneratedFragmentId(fragment.getId());

                    LOGGER.info("Generated news fragment saved and added to scene: {}", fragment.getId());
                    return fragment;
                });
    }
}
