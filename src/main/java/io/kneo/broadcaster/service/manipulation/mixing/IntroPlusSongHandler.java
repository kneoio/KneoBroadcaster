package io.kneo.broadcaster.service.manipulation.mixing;

import io.kneo.broadcaster.dto.cnst.AiAgentStatus;
import io.kneo.broadcaster.dto.mcp.AddToQueueMcpDTO;
import io.kneo.broadcaster.model.FileMetadata;
import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.model.SoundFragment;
import io.kneo.broadcaster.model.live.LiveSoundFragment;
import io.kneo.broadcaster.repository.soundfragment.SoundFragmentRepository;
import io.kneo.broadcaster.service.playlist.PlaylistManager;
import io.kneo.broadcaster.service.soundfragment.SoundFragmentService;
import io.kneo.core.model.user.SuperUser;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class IntroPlusSongHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(IntroPlusSongHandler.class);
    private final SoundFragmentRepository repository;
    private final AudioMergerService audioMergerService;
    private final SoundFragmentService soundFragmentService;

    public IntroPlusSongHandler(SoundFragmentRepository repository,
                                AudioMergerService audioMergerService,
                                SoundFragmentService soundFragmentService) {
        this.repository = repository;
        this.audioMergerService = audioMergerService;
        this.soundFragmentService = soundFragmentService;
    }


    public Uni<Boolean> handle(RadioStation radioStation, AddToQueueMcpDTO toQueueDTO) {
        PlaylistManager playlistManager = radioStation.getStreamManager().getPlaylistManager();
        UUID soundFragmentId = toQueueDTO.getSoundFragments().get("song1");
        String ttsFilePath = toQueueDTO.getFilePaths().get("audio1");

        return soundFragmentService.getById(soundFragmentId, SuperUser.build())
                .chain(soundFragment -> {
                    return repository.getFirstFile(soundFragment.getId())
                            .chain(songMetadata -> {
                                if (playlistManager.getPrioritizedQueue().size() > 2) {
                                    LiveSoundFragment lastFragment = playlistManager.getPrioritizedQueue().getLast();
                                    LOGGER.info("prioritizedQueue: {}", lastFragment.getMetadata());
                                }

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
        Path path = Path.of(ttsFilePath);
        return audioMergerService.mergeAudioFiles(path, songMetadata, radioStation)
                .onItem().transform(mergedPath -> {
                    songMetadata.setTemporaryFilePath(mergedPath);
                    return songMetadata;
                })
                .onItem().transform(finalMetadata -> {
                    soundFragment.setFileMetadataList(List.of(finalMetadata));
                    soundFragment.setTitle(soundFragment.getTitle());
                    return finalMetadata;
                })
                .chain(updatedMetadata -> {
                    updateRadioStationStatus(radioStation);
                    return playlistManager.addFragmentToSlice(soundFragment, toQueueDTO.getPriority(),
                                    radioStation.getBitRate(), MergingType.INTRO_PLUS_SONG)
                            .onItem().invoke(result -> {
                                if (result) {
                                    LOGGER.info("Added merged song to queue: {}", soundFragment.getTitle());
                                }
                            });
                });
    }

    private Uni<Boolean> handleWithoutTtsFile(RadioStation radioStation, AddToQueueMcpDTO toQueueDTO,
                                              SoundFragment soundFragment, PlaylistManager playlistManager) {
        updateRadioStationStatus(radioStation);
        return playlistManager.addFragmentToSlice(soundFragment, toQueueDTO.getPriority(),
                        radioStation.getBitRate(), MergingType.NOT_MIXED)
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