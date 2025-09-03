package io.kneo.broadcaster.service.manipulation.mixing.handler;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.dto.cnst.AiAgentStatus;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

public class IntroSongHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(IntroSongHandler.class);
    private final SoundFragmentRepository repository;
    private final SoundFragmentService soundFragmentService;
    private final AiAgentService aiAgentService;
    private final BroadcasterConfig config;
    private final AudioConcatenator audioConcatenator;
    private final String tempBaseDir;

    public IntroSongHandler(BroadcasterConfig config,
                            SoundFragmentRepository repository,
                            SoundFragmentService soundFragmentService,
                            AiAgentService aiAgentService,
                            FFmpegProvider fFmpegProvider) throws IOException, AudioMergeException {
        this.config = config;
        this.repository = repository;
        this.soundFragmentService = soundFragmentService;
        this.aiAgentService = aiAgentService;
        this.audioConcatenator = new AudioConcatenator(config, fFmpegProvider);
        this.tempBaseDir = config.getPathUploads() + "/audio-processing";
    }

    public Uni<Boolean> handle(RadioStation radioStation, AddToQueueMcpDTO toQueueDTO) {
        PlaylistManager playlistManager = radioStation.getStreamManager().getPlaylistManager();
        UUID soundFragmentId = toQueueDTO.getSoundFragments().get("song1");
        String ttsFilePath = toQueueDTO.getFilePaths().get("audio1");

        return soundFragmentService.getById(soundFragmentId, SuperUser.build())
                .chain(soundFragment -> {
                    return repository.getFirstFile(soundFragment.getId())
                            .chain(songMetadata -> {
                                if (ttsFilePath != null) {
                                    return handleWithTtsFile(radioStation, toQueueDTO, soundFragment, songMetadata, ttsFilePath, playlistManager);
                                } else {
                                    return handleWithoutTtsFile(radioStation, toQueueDTO, soundFragment, playlistManager);
                                }
                            });
                });
    }

    private Uni<Boolean> handleWithTtsFile(RadioStation radioStation, AddToQueueMcpDTO toQueueDTO,
                                           SoundFragment soundFragment, FileMetadata songMetadata, String ttsFilePath,
                                           PlaylistManager playlistManager) {
        return aiAgentService.getById(radioStation.getAiAgentId(), SuperUser.build(), LanguageCode.en)
                .chain(aiAgent -> {
                    double gainValue = aiAgent.getMerger().getGainIntro();

                    return songMetadata.materializeFileStream(tempBaseDir)
                            .chain(songTempFile -> {
                                String outputPath = config.getPathForMerged() + "/merged_intro_" +
                                        soundFragment.getSlugName() + "_" + System.currentTimeMillis() + "." +
                                        config.getAudioOutputFormat();

                                return audioConcatenator.concatenate(
                                        ttsFilePath,
                                        songTempFile.toString(),
                                        outputPath,
                                        ConcatenationType.DIRECT_CONCAT,
                                        gainValue
                                );
                            })
                            .onItem().transform(mergedPath -> {
                                FileMetadata mergedMetadata = new FileMetadata();
                                mergedMetadata.setTemporaryFilePath(Path.of(mergedPath));
                                soundFragment.setFileMetadataList(List.of(mergedMetadata));
                                return mergedMetadata;
                            })
                            .chain(updatedMetadata -> {
                                updateRadioStationStatus(radioStation);
                                return playlistManager.addFragmentToSlice(soundFragment, toQueueDTO.getPriority(),
                                                radioStation.getBitRate(), toQueueDTO.getMergingMethod())
                                        .onItem().invoke(result -> {
                                            if (result) {
                                                LOGGER.info("Added merged song to queue: {}", soundFragment.getTitle());
                                            }
                                        });
                            });
                });
    }

    private Uni<Boolean> handleWithoutTtsFile(RadioStation radioStation, AddToQueueMcpDTO toQueueDTO,
                                              SoundFragment soundFragment, PlaylistManager playlistManager) {
        updateRadioStationStatus(radioStation);
        return playlistManager.addFragmentToSlice(soundFragment, toQueueDTO.getPriority(),
                        radioStation.getBitRate(), toQueueDTO.getMergingMethod())
                .onItem().invoke(result -> {
                    if (result) {
                        LOGGER.info("Added song to queue: {}", soundFragment.getTitle());
                    }
                });
    }

    private void updateRadioStationStatus(RadioStation radioStation) {
        radioStation.setAiAgentStatus(AiAgentStatus.CONTROLLING);
        radioStation.setLastAgentContactAt(System.currentTimeMillis());
    }
}