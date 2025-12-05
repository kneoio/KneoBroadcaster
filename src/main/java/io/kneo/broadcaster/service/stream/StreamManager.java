package io.kneo.broadcaster.service.stream;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.config.HlsPlaylistConfig;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.model.cnst.ManagedBy;
import io.kneo.broadcaster.model.live.LiveSoundFragment;
import io.kneo.broadcaster.model.radiostation.RadioStation;
import io.kneo.broadcaster.service.live.AiHelperService;
import io.kneo.broadcaster.service.manipulation.segmentation.AudioSegmentationService;
import io.kneo.broadcaster.service.playlist.PlaylistManager;
import io.kneo.broadcaster.service.playlist.SongSupplier;
import io.kneo.broadcaster.service.soundfragment.BrandSoundFragmentUpdateService;
import io.kneo.broadcaster.service.soundfragment.SoundFragmentService;
import io.smallrye.mutiny.subscription.Cancellable;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StreamManager implements IStreamManager {
    private static final ZoneId ZONE_ID = ZoneId.of("Europe/Lisbon");
    private static final Logger LOGGER = LoggerFactory.getLogger(StreamManager.class);
    private static final Pattern SEGMENT_PATTERN = Pattern.compile("([^_]+)_([0-9]+)\\.ts$");

    private final ConcurrentSkipListMap<Long, HlsSegment> liveSegments = new ConcurrentSkipListMap<>();
    private final AtomicLong currentSequence = new AtomicLong(0);
    private final Queue<HlsSegment> pendingFragmentSegmentsQueue = new LinkedList<>();
    private static final int SEGMENTS_TO_DRIP_PER_FEED_CALL = 1;

    @Getter
    @Setter
    private RadioStation radioStation;
    @Getter
    private PlaylistManager playlistManager;
    private final BroadcasterConfig broadcasterConfig;
    @Getter
    private final HlsPlaylistConfig config;
    @Getter
    private final SoundFragmentService soundFragmentService;
    @Getter
    private final AudioSegmentationService segmentationService;
    private final SongSupplier songSupplier;
    private final SegmentFeederTimer segmentFeederTimer;
    private final SliderTimer sliderTimer;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    private final int maxVisibleSegments = 20; //was 10
    private static final int PENDING_QUEUE_REFILL_THRESHOLD = 10; //was 5

    private final Map<String, Cancellable> timerSubscriptions = new ConcurrentHashMap<>();
    private final BrandSoundFragmentUpdateService updateService;
    private final Object fragmentRetrievalLock = new Object();
    private final AiHelperService aiHelperService;

    public StreamManager(
            SliderTimer sliderTimer,
            SegmentFeederTimer segmentFeederTimer,
            BroadcasterConfig broadcasterConfig,
            HlsPlaylistConfig config,
            SoundFragmentService soundFragmentService,
            AudioSegmentationService segmentationService, SongSupplier songSupplier,
            BrandSoundFragmentUpdateService updateService,
            AiHelperService aiHelperService
    ) {
        this.sliderTimer = sliderTimer;
        this.segmentFeederTimer = segmentFeederTimer;
        this.broadcasterConfig = broadcasterConfig;
        this.config = config;
        this.soundFragmentService = soundFragmentService;
        this.segmentationService = segmentationService;
        this.songSupplier = songSupplier;
        this.updateService = updateService;
        this.aiHelperService = aiHelperService;
    }

    @Override
    public void initialize() {
        this.radioStation.setStatus(RadioStationStatus.WARMING_UP);
        LOGGER.info("New broadcast initialized for {}", radioStation.getSlugName());

        playlistManager = new PlaylistManager(
                config,
                broadcasterConfig,
                this,
                songSupplier,
                updateService,
                aiHelperService
        );
        if (radioStation.getManagedBy() == ManagedBy.ITSELF) {
            playlistManager.startSelfManaging();
        }

        Cancellable feeder = segmentFeederTimer.getTicker().subscribe().with(
                timestamp -> executorService.submit(this::feedSegments),
                error -> LOGGER.error("Feeder subscription error for {}: {}", radioStation.getSlugName(), error.getMessage(), error)
        );

        Cancellable slider = sliderTimer.getTicker().subscribe().with(
                timestamp -> executorService.submit(this::slideWindow),
                error -> LOGGER.error("Slider subscription error for {}: {}", radioStation.getSlugName(), error.getMessage(), error)
        );

        timerSubscriptions.put("feeder", feeder);
        timerSubscriptions.put("slider", slider);
    }

    public void feedSegments() {
        if (!pendingFragmentSegmentsQueue.isEmpty()) {
            for (int i = 0; i < SEGMENTS_TO_DRIP_PER_FEED_CALL; i++) {
                if (liveSegments.size() >= maxVisibleSegments * 2) {
                    System.out.printf("feedSegments Debug: [DRIP] liveSegments buffer for %s is full or at limit (%d/%d). Pausing drip-feed for this call.%n",
                            radioStation.getSlugName(), liveSegments.size(), maxVisibleSegments * 2);
                    break;
                }
                HlsSegment segmentToMakeLive = pendingFragmentSegmentsQueue.poll();
                liveSegments.put(segmentToMakeLive.getSequence(), segmentToMakeLive);

            }
        }

        if (pendingFragmentSegmentsQueue.size() < PENDING_QUEUE_REFILL_THRESHOLD) {
            try {
                synchronized (fragmentRetrievalLock) {
                    LiveSoundFragment fragment = playlistManager.getNextFragment();
                    if (fragment != null && !fragment.getSegments().isEmpty()) {
                        final long[] firstSeqInBatch = {-1L};
                        final long[] lastSeqInBatch = {-1L};

                        boolean isFirst = true;
                        for (HlsSegment segment : fragment.getSegments().get(radioStation.getBitRate())) {
                            long seq = currentSequence.getAndIncrement();
                            if (firstSeqInBatch[0] == -1L) {
                                firstSeqInBatch[0] = seq;
                            }
                            lastSeqInBatch[0] = seq;
                            segment.setSequence(seq);
                            segment.setLiveSoundFragment(fragment);
                            segment.setFirstSegmentOfFragment(isFirst);
                            pendingFragmentSegmentsQueue.offer(segment);
                            isFirst = false;
                        }
                    }
                }
            } catch (Exception e) {
                // Error handling
            }
        }
    }

    private void slideWindow() {
        if (liveSegments.isEmpty()) {
            return;
        }
      /*  System.out.printf("slideWindow Debug: Checking window for %s. Current segments in liveSegments: %d, Max visible in playlist: %d%n",
                radioStation.getSlugName(), liveSegments.size(), maxVisibleSegments);*/
        int removedCount = 0;
        while (liveSegments.size() > maxVisibleSegments) {
            long removedKey = liveSegments.firstKey();
            liveSegments.pollFirstEntry();
            removedCount++;
           /* System.out.printf("slideWindow Debug: Removed segment for %s with sequence %d. liveSegments now: %d%n",
                    radioStation.getSlugName(), removedKey, liveSegments.size());*/
        }
        if (removedCount > 0) {
           /* System.out.printf("slideWindow Debug: Finished sliding for %s. Removed %d segment(s). liveSegments now: %d. First key in liveSegments: %s%n",
                    radioStation.getSlugName(), removedCount, liveSegments.size(), liveSegments.isEmpty() ? "N/A" : liveSegments.firstKey());*/
        } else {
          /*  System.out.printf("slideWindow Debug: No segments needed removal for %s during this pass. liveSegments size: %d. First key: %s%n",
                    radioStation.getSlugName(), liveSegments.size(), liveSegments.isEmpty() ? "N/A" : liveSegments.firstKey());*/
        }
    }

    @Override
    public String generatePlaylist() {
        if (liveSegments.isEmpty()) {
            //System.out.printf("generatePlaylist Debug: Playlist for %s: MEDIA-SEQUENCE=0, Segments=[] (Live segments empty)%n", radioSlugForDebug);
            return "#EXTM3U\n" +
                    "#EXT-X-VERSION:3\n" +
                    "#EXT-X-ALLOW-CACHE:NO\n" +
                    "#EXT-X-TARGETDURATION:" + config.getSegmentDuration() + "\n" +
                    "#EXT-X-MEDIA-SEQUENCE:0\n";
        }

        StringBuilder playlist = new StringBuilder();
        playlist.append("#EXTM3U\n")
                .append("#EXT-X-VERSION:3\n")
                .append("#EXT-X-ALLOW-CACHE:NO\n")
                .append("#EXT-X-PLAYLIST-TYPE:EVENT\n")
                .append("#EXT-X-TARGETDURATION:").append(config.getSegmentDuration()).append("\n");

        long firstSequenceInWindow = liveSegments.firstKey();
        playlist.append("#EXT-X-MEDIA-SEQUENCE:").append(firstSequenceInWindow).append("\n");
        playlist.append("#EXT-X-PROGRAM-DATE-TIME:")
                .append(ZonedDateTime.now(ZONE_ID).format(DateTimeFormatter.ISO_INSTANT))
                .append("\n");

        String currentRadioSlugForPath = (this.radioStation != null && this.radioStation.getSlugName() != null)
                ? this.radioStation.getSlugName() : "default_station_path";


        liveSegments.tailMap(firstSequenceInWindow).entrySet().stream()
                .limit(maxVisibleSegments)
                .forEach(entry -> {
                    HlsSegment segment = entry.getValue();
                    playlist.append("#EXTINF:")
                            .append(segment.getDuration())
                            .append(",")
                            .append(segment.getSongMetadata().toString())
                            .append("\n")
                            .append("segments/")
                            .append(currentRadioSlugForPath)
                            .append("_")
                            .append(segment.getSequence())
                            .append(".ts\n");
                });

        return playlist.toString();
    }

    @Override
    public HlsSegment getSegment(String segmentParam) {
        try {
            Matcher matcher = SEGMENT_PATTERN.matcher(segmentParam);
            if (!matcher.find()) {
                LOGGER.warn("Segment '{}' doesn't match expected pattern: {}", segmentParam, SEGMENT_PATTERN.pattern());
                return null;
            }
            long segmentSequence = Long.parseLong(matcher.group(2));
            HlsSegment segment = liveSegments.get(segmentSequence);

            if (segment == null) {
                LOGGER.debug("Segment {} not found in liveSegments", segmentSequence);
            }
            return segment;
        } catch (Exception e) {
            LOGGER.warn("Error processing segment request '{}' : {}", segmentParam, e.getMessage(), e);
            return null;
        }
    }


    @Override
    public StreamManagerStats getStats() {
        return new StreamManagerStats(
                Map.copyOf(liveSegments),
                getSegmentHeartbeat()
        );
    }


    @Override
    public void shutdown() {
        LOGGER.info("Shutting down StreamManager for: {}", radioStation.getSlugName());
        timerSubscriptions.forEach((key, subscription) -> {
            if (subscription != null) {
                subscription.cancel();
            }
        });
        timerSubscriptions.clear();
        executorService.shutdownNow();
        currentSequence.set(0);
        liveSegments.clear();
        pendingFragmentSegmentsQueue.clear();
        LOGGER.info("StreamManager for {} has been shut down. All queues cleared.", radioStation.getSlugName());
        if (radioStation != null) {
            radioStation.setStatus(RadioStationStatus.OFF_LINE);
        }
    }

    public boolean getSegmentHeartbeat() {
        return !liveSegments.isEmpty();
    }
}