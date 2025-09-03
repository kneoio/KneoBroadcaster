package io.kneo.broadcaster.service.manipulation.mixing.handler;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.dto.mcp.AddToQueueMcpDTO;
import io.kneo.broadcaster.model.FileMetadata;
import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.model.SoundFragment;
import io.kneo.broadcaster.repository.soundfragment.SoundFragmentRepository;
import io.kneo.broadcaster.service.AiAgentService;
import io.kneo.broadcaster.service.exceptions.AudioMergeException;
import io.kneo.broadcaster.service.manipulation.FFmpegProvider;
import io.kneo.broadcaster.service.manipulation.mixing.AudioConcatenator;
import io.kneo.broadcaster.service.manipulation.mixing.ConcatenationType;
import io.kneo.broadcaster.service.playlist.PlaylistManager;
import io.kneo.broadcaster.service.soundfragment.SoundFragmentService;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.SuperUser;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import net.bramp.ffmpeg.builder.FFmpegBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

public class AudioMixingHandler extends MixingHandlerBase {
    private static final Logger LOGGER = LoggerFactory.getLogger(AudioMixingHandler.class);
    private static final int SAMPLE_RATE = 44100;
    private final SoundFragmentRepository repository;
    private final SoundFragmentService soundFragmentService;
    private final AiAgentService aiAgentService;
    private final String outputDir;
    private final String tempBaseDir;
    private final AudioConcatenator audioConcatenator;

    public AudioMixingHandler(BroadcasterConfig config,
                              SoundFragmentRepository repository,
                              SoundFragmentService soundFragmentService,
                              AiAgentService aiAgentService,
                              FFmpegProvider fFmpegProvider) throws IOException, AudioMergeException {
        super(fFmpegProvider);
        this.repository = repository;
        this.soundFragmentService = soundFragmentService;
        this.aiAgentService = aiAgentService;
        this.outputDir = config.getPathForMerged();
        this.tempBaseDir = config.getPathUploads() + "/audio-processing";
        this.audioConcatenator = new AudioConcatenator(config, fFmpegProvider);

    }

    public Uni<Boolean> handle(RadioStation radioStation, AddToQueueMcpDTO toQueueDTO) {
        PlaylistManager playlistManager = radioStation.getStreamManager().getPlaylistManager();
        UUID soundFragmentId1 = toQueueDTO.getSoundFragments().get("song1");
        String introSongPath = toQueueDTO.getFilePaths().get("audio1");
        UUID soundFragmentId2 = toQueueDTO.getSoundFragments().get("song2");
        MixingProfile settings = MixingProfile.randomProfile(12345L);
        LOGGER.info("Applied Mixing {}", settings.description);

        return aiAgentService.getById(radioStation.getAiAgentId(), SuperUser.build(), LanguageCode.en)
                .chain(aiAgent -> {
                    double gainValue = aiAgent.getMerger().getGainIntro();

                    return soundFragmentService.getById(soundFragmentId1, SuperUser.build())
                            .chain(soundFragment1 -> {
                                return repository.getFirstFile(soundFragment1.getId())
                                        .chain(songMetadata1 -> {
                                            return songMetadata1.materializeFileStream(tempBaseDir)
                                                    .chain(tempPath1 -> {
                                                        String tempMixPath = outputDir + "/temp_mix_" +
                                                                soundFragment1.getSlugName() + "_i_" +
                                                                System.currentTimeMillis() + ".wav";
                                                        return createOutroIntroMix(
                                                                tempPath1.toString(),
                                                                introSongPath,
                                                                tempMixPath,
                                                                settings,
                                                                gainValue
                                                        );
                                                    })
                                                    .chain(actualTempMixPath -> {
                                                        return soundFragmentService.getById(soundFragmentId2, SuperUser.build())
                                                                .chain(soundFragment2 -> {
                                                                    return repository.getFirstFile(soundFragment2.getId())
                                                                            .chain(songMetadata2 -> {
                                                                                return songMetadata2.materializeFileStream(tempBaseDir)
                                                                                        .chain(tempPath2 -> {
                                                                                            String finalMixPath = outputDir + "/final_mix_" +
                                                                                                    soundFragment1.getSlugName() + "_i_" +
                                                                                                    soundFragment2.getSlugName() + "_" +
                                                                                                    System.currentTimeMillis() + ".wav";
                                                                                            return audioConcatenator.concatenate(
                                                                                                    actualTempMixPath,
                                                                                                    tempPath2.toString(),
                                                                                                    finalMixPath,
                                                                                                    ConcatenationType.DIRECT_CONCAT,
                                                                                                    1.0
                                                                                            );
                                                                                        });
                                                                            });
                                                                });
                                                    })
                                                    .chain(finalPath -> {
                                                        SoundFragment soundFragment = new SoundFragment();
                                                        soundFragment.setId(UUID.randomUUID());
                                                        soundFragment.setTitle(soundFragment1.getTitle());
                                                        soundFragment.setArtist(soundFragment1.getArtist());
                                                        FileMetadata fileMetadata = new FileMetadata();
                                                        fileMetadata.setTemporaryFilePath(Path.of(finalPath));
                                                        soundFragment.setFileMetadataList(List.of(fileMetadata));
                                                        return playlistManager.addFragmentToSlice(soundFragment, toQueueDTO.getPriority(),
                                                                radioStation.getBitRate(), toQueueDTO.getMergingMethod());
                                                    });
                                        });
                            });
                });
    }

    public Uni<String> createOutroIntroMix(String mainSongPath, String introSongPath, String outputPath, MixingProfile settings, double gainValue) {
        return Uni.createFrom().item(() -> {
            try {
                double mainDuration = getAudioDuration(mainSongPath);
                double introDuration = getAudioDuration(introSongPath);

                LOGGER.info("Main song duration: {}s, Intro duration: {}s", mainDuration, introDuration);

                // When should the intro start? (e.g., 5 seconds before end)
                double introStartSeconds = settings.introStartEarly > 0
                        ? Math.max(0, mainDuration - settings.introStartEarly)
                        : Math.max(0, mainDuration - 5); // default: start 5s before end

                // When should fade-out of main song begin?
                // Fade starts a bit *before* intro (e.g., 3 seconds before intro starts)
                double fadeLeadIn = 3.0; // seconds before intro starts, fade begins
                double fadeStartTime = Math.max(0, introStartSeconds - fadeLeadIn);

                // Fade duration: from fadeStartTime to end of main song
                double fadeDuration = mainDuration - fadeStartTime;


                String filterComplex = buildFilter(
                        fadeStartTime,
                        fadeDuration,
                        introStartSeconds,
                        settings.fadeCurve,
                        settings.fadeToVolume,
                        gainValue
                );

                FFmpegBuilder builder = new FFmpegBuilder()
                        .setInput(mainSongPath)
                        .addInput(introSongPath)
                        .setComplexFilter(filterComplex)
                        .addOutput(outputPath)
                        .setAudioCodec("pcm_s16le")
                        .setAudioSampleRate(SAMPLE_RATE)
                        .setAudioChannels(2)
                        .done();

                executor.createJob(builder).run();
                LOGGER.info("Successfully created outro-intro mix: {}", outputPath);
                return outputPath;

            } catch (Exception e) {
                LOGGER.error("Error creating outro-intro mix with FFmpeg: {}", e.getMessage(), e);
                throw new RuntimeException("Failed to create outro-intro mix", e);
            }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    private String buildFilter(
            double fadeStartTime,
            double fadeDuration,
            double introStartTime,
            FadeCurve curve,
            double fadeDownTo,
            double gainValue
    ) {

        String mainFilter;
        if (fadeDownTo == 0.0) {
            mainFilter = String.format("afade=t=out:st=%.2f:d=%.2f:curve=%s",
                    fadeStartTime, fadeDuration, curve.getFfmpegValue());
        } else {
            //TODO it is not working
            mainFilter = String.format(
                    "volume='min(1,max(%.2f,1-(1-%.2f)*(t-%.2f)/%.2f))':eval=frame",
                    fadeDownTo, fadeDownTo, fadeStartTime, fadeDuration);

        }
        return String.format("[0:a]%s[mainfaded];",mainFilter) +
                String.format("[1:a]volume=%.2f,adelay=%.0f|%.0f[intro];",
                        gainValue,
                        introStartTime * 1000,
                        introStartTime * 1000) +
                "[mainfaded][intro]amix=inputs=2:duration=longest:dropout_transition=0:normalize=0";
    }

}