package io.kneo.broadcaster.service.live;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.dto.mcp.AddToQueueMcpDTO;
import io.kneo.broadcaster.model.ScriptScene;
import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.model.radiostation.RadioStation;
import io.kneo.broadcaster.model.soundfragment.SoundFragment;
import io.kneo.broadcaster.repository.soundfragment.SoundFragmentRepository;
import io.kneo.broadcaster.service.AiAgentService;
import io.kneo.broadcaster.service.exceptions.AudioMergeException;
import io.kneo.broadcaster.service.manipulation.FFmpegProvider;
import io.kneo.broadcaster.service.manipulation.mixing.AudioConcatenator;
import io.kneo.broadcaster.service.manipulation.mixing.ConcatenationType;
import io.kneo.broadcaster.service.manipulation.mixing.MergingType;
import io.kneo.broadcaster.service.manipulation.mixing.handler.AudioMixingHandler;
import io.kneo.broadcaster.service.playlist.SongSupplier;
import io.kneo.broadcaster.service.soundfragment.SoundFragmentService;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

@ApplicationScoped
public class JinglePlaybackHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(JinglePlaybackHandler.class);

    private final SoundFragmentService soundFragmentService;
    private final SongSupplier songSupplier;
    private final BroadcasterConfig broadcasterConfig;
    private final SoundFragmentRepository soundFragmentRepository;
    private final FFmpegProvider fFmpegProvider;
    private final AudioConcatenator audioConcatenator;
    private final AiAgentService aiAgentService;

    @Inject
    public JinglePlaybackHandler(
            SoundFragmentService soundFragmentService,
            SongSupplier songSupplier,
            BroadcasterConfig broadcasterConfig,
            SoundFragmentRepository soundFragmentRepository,
            FFmpegProvider fFmpegProvider,
            AudioConcatenator audioConcatenator,
            AiAgentService aiAgentService
    ) {
        this.soundFragmentService = soundFragmentService;
        this.songSupplier = songSupplier;
        this.broadcasterConfig = broadcasterConfig;
        this.soundFragmentRepository = soundFragmentRepository;
        this.fFmpegProvider = fFmpegProvider;
        this.audioConcatenator = audioConcatenator;
        this.aiAgentService = aiAgentService;
    }

    public void handleJinglePlayback(RadioStation station, ScriptScene scene) {
        LOGGER.info("Station '{}': Playing jingle instead of DJ intro (talkativity: {})",
                station.getSlugName(), scene.getTalkativity());

        boolean useJingle = new Random().nextBoolean();
        
        if (useJingle) {
            handleJingleAndSong(station);
        } else {
            handleTwoSongs(station);
        }
    }

    private void handleJingleAndSong(RadioStation station) {
        Uni.combine().all()
                .unis(
                        soundFragmentService.getByTypeAndBrand(PlaylistItemType.JINGLE, station.getId()),
                        songSupplier.getNextSong(station.getSlugName(), PlaylistItemType.SONG, 1)
                )
                .asTuple()
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .subscribe().with(
                        tuple -> {
                            List<SoundFragment> jingles = tuple.getItem1();
                            List<SoundFragment> songs = tuple.getItem2();

                            if (jingles.isEmpty()) {
                                LOGGER.warn("Station '{}': No jingles available for playback", station.getSlugName());
                                return;
                            }

                            SoundFragment selectedJingle = jingles.get(new Random().nextInt(jingles.size()));
                            SoundFragment selectedSong = songs.get(0);

                            LOGGER.info("Station '{}': Concatenating jingle '{}' with song '{}'",
                                    station.getSlugName(), selectedJingle.getTitle(), selectedSong.getTitle());

                            AddToQueueMcpDTO queueDTO = new AddToQueueMcpDTO();
                            queueDTO.setMergingMethod(MergingType.FILLER_JINGLE);
                            queueDTO.setPriority(11); // less than dj agent(10)

                            Map<String, UUID> soundFragments = new HashMap<>();
                            soundFragments.put("song1", selectedJingle.getId());
                            soundFragments.put("song2", selectedSong.getId());
                            queueDTO.setSoundFragments(soundFragments);

                            concatenate(station, queueDTO, "jingle + song");
                        },
                        failure -> LOGGER.error("Station '{}': Failed to fetch jingles/songs: {}",
                                station.getSlugName(), failure.getMessage(), failure)
                );
    }

    private void handleTwoSongs(RadioStation station) {
        songSupplier.getNextSong(station.getSlugName(), PlaylistItemType.SONG, 2)
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .subscribe().with(
                        songs -> {

                            SoundFragment firstSong = songs.get(0);
                            SoundFragment secondSong = songs.get(1);

                            LOGGER.info("Station '{}': Concatenating song '{}' with song '{}'",
                                    station.getSlugName(), firstSong.getTitle(), secondSong.getTitle());

                            AddToQueueMcpDTO queueDTO = new AddToQueueMcpDTO();
                            queueDTO.setMergingMethod(MergingType.SONG_CROSSFADE_SONG);
                            queueDTO.setPriority(12);

                            Map<String, UUID> soundFragments = new HashMap<>();
                            soundFragments.put("song1", firstSong.getId());
                            soundFragments.put("song2", secondSong.getId());
                            queueDTO.setSoundFragments(soundFragments);

                            concatenate(station, queueDTO, "song + crossfade + song");
                        },
                        failure -> LOGGER.error("Station '{}': Failed to fetch songs: {}",
                                station.getSlugName(), failure.getMessage(), failure)
                );
    }

    private void concatenate(RadioStation station, AddToQueueMcpDTO queueDTO, String type) {
        try {
            AudioMixingHandler handler = new AudioMixingHandler(
                    broadcasterConfig,
                    soundFragmentRepository,
                    soundFragmentService,
                    audioConcatenator,
                    aiAgentService,
                    fFmpegProvider
            );

            handler.handleConcatenationAndFeed(station, queueDTO, ConcatenationType.CROSSFADE)
                    .subscribe().with(
                            success -> LOGGER.info("Station '{}': Successfully queued {} concatenation",
                                    station.getSlugName(), type),
                            failure -> LOGGER.error("Station '{}': Failed to concatenate {}: {}",
                                    station.getSlugName(), type, failure.getMessage(), failure)
                    );
        } catch (IOException | AudioMergeException e) {
            LOGGER.error("Station '{}': Failed to create AudioMixingHandler: {}",
                    station.getSlugName(), e.getMessage(), e);
        }
    }
}
