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

/**
 * Handles jingle playback functionality for radio stations.
 * Manages the selection and concatenation of jingles with songs.
 */
@ApplicationScoped
public class JinglePlaybackHandler {
    private static final Logger log = LoggerFactory.getLogger(JinglePlaybackHandler.class);

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

    /**
     * Handles jingle playback by concatenating a random jingle with the next song.
     * This runs asynchronously in a separate thread.
     *
     * @param station The radio station
     * @param scene The active script scene
     */
    public void handleJinglePlayback(RadioStation station, ScriptScene scene) {
        log.info("Station '{}': Playing jingle instead of DJ intro (talkativity: {})",
                station.getSlugName(), scene.getTalkativity());

        // Start async processing but don't wait for it
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
                                log.warn("Station '{}': No jingles available for playback", station.getSlugName());
                                return;
                            }

                            if (songs.isEmpty()) {
                                log.warn("Station '{}': No songs available for playback", station.getSlugName());
                                return;
                            }

                            // Select random jingle
                            SoundFragment selectedJingle = jingles.get(new Random().nextInt(jingles.size()));
                            SoundFragment selectedSong = songs.get(0);

                            log.info("Station '{}': Concatenating jingle '{}' with song '{}'",
                                    station.getSlugName(), selectedJingle.getTitle(), selectedSong.getTitle());

                            // Create DTO for concatenation
                            AddToQueueMcpDTO queueDTO = new AddToQueueMcpDTO();
                            queueDTO.setMergingMethod(MergingType.FILLER_SONG);
                            queueDTO.setPriority(1);

                            Map<String, UUID> soundFragments = new HashMap<>();
                            soundFragments.put("song1", selectedJingle.getId());
                            soundFragments.put("song2", selectedSong.getId());
                            queueDTO.setSoundFragments(soundFragments);

                            try {
                                AudioMixingHandler handler = new AudioMixingHandler(
                                        broadcasterConfig,
                                        soundFragmentRepository,
                                        soundFragmentService,
                                        audioConcatenator,
                                        aiAgentService,
                                        fFmpegProvider
                                );

                                handler.handleConcatenation(station, queueDTO, ConcatenationType.CROSSFADE)
                                        .subscribe().with(
                                                success -> log.info("Station '{}': Successfully queued jingle+song concatenation",
                                                        station.getSlugName()),
                                                failure -> log.error("Station '{}': Failed to concatenate jingle+song: {}",
                                                        station.getSlugName(), failure.getMessage(), failure)
                                        );
                            } catch (IOException | AudioMergeException e) {
                                log.error("Station '{}': Failed to create AudioMixingHandler: {}",
                                        station.getSlugName(), e.getMessage(), e);
                            }
                        },
                        failure -> log.error("Station '{}': Failed to fetch jingles/songs: {}",
                                station.getSlugName(), failure.getMessage(), failure)
                );
    }
}
