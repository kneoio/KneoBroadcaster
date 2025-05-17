package io.kneo.broadcaster.controller.stream;

import io.kneo.broadcaster.config.HlsPlaylistConfig;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.model.BrandSoundFragment;
import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.model.cnst.ManagedBy;
import io.kneo.broadcaster.service.SoundFragmentService;
import io.kneo.broadcaster.service.manipulation.AudioSegmentationService;
import io.kneo.broadcaster.service.radio.PlaylistManager;
import io.kneo.broadcaster.service.stream.SegmentFeederTimer;
import io.kneo.broadcaster.service.stream.SliderTimer;
import io.smallrye.mutiny.subscription.Cancellable;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.LinkedList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class StreamManager implements IStreamManager {
    private static final ZoneId ZONE_ID = ZoneId.of("Europe/Lisbon");
    private static final Logger LOGGER = LoggerFactory.getLogger(StreamManager.class);
    private static final Pattern SEGMENT_PATTERN = Pattern.compile("([^_]+)_([0-9]+)\\.ts$");

    private final ConcurrentSkipListMap<Long, HlsSegment> liveSegments = new ConcurrentSkipListMap<>();
    private final AtomicLong currentSequence = new AtomicLong(0);
    private long latestRequestedSegment = 0;

    private final Queue<HlsSegment> pendingFragmentSegmentsQueue = new LinkedList<>();
    private static final int SEGMENTS_TO_DRIP_PER_FEED_CALL = 1;
    private static final int PENDING_QUEUE_REFILL_THRESHOLD = 5;

    @Getter @Setter
    private RadioStation radioStation; // This is set by RadioStationPool before initialize() is called
    @Getter
    private PlaylistManager playlistManager;
    @Getter
    private final HlsPlaylistConfig config;
    @Getter
    private HLSPlaylistStats stats;
    @Getter
    private final SoundFragmentService soundFragmentService;
    @Getter
    private final AudioSegmentationService segmentationService;

    private final SegmentFeederTimer segmentFeederTimer;
    private final SliderTimer sliderTimer;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    private final int maxVisibleSegments;
    private final Map<String, Cancellable> timerSubscriptions = new ConcurrentHashMap<>();

    public StreamManager(
            SliderTimer sliderTimer,
            SegmentFeederTimer segmentFeederTimer,
            HlsPlaylistConfig config,
            SoundFragmentService soundFragmentService,
            AudioSegmentationService segmentationService,
            int maxVisibleSegments
    ) {
        this.sliderTimer = sliderTimer;
        this.segmentFeederTimer = segmentFeederTimer;
        this.config = config;
        this.soundFragmentService = soundFragmentService;
        this.segmentationService = segmentationService;
        this.maxVisibleSegments = maxVisibleSegments;
    }

    @Override
    public void initialize() {
        String radioSlugForLog;
        if (this.radioStation != null) {
            radioSlugForLog = (this.radioStation.getSlugName() != null) ? this.radioStation.getSlugName() : "UNKNOWN_STATION";
            if (this.radioStation.getManagedBy() == ManagedBy.ITSELF || this.radioStation.getManagedBy() == ManagedBy.MIX) {
                this.radioStation.setStatus(RadioStationStatus.WARMING_UP);
                System.out.println(String.format("StreamManager.initialize Debug: Station %s status set to WARMING_UP by StreamManager.", radioSlugForLog));
            } else {
                this.radioStation.setStatus(RadioStationStatus.WAITING_FOR_CURATOR);
                System.out.println(String.format("StreamManager.initialize Debug: Station %s status set to WAITING_FOR_CURATOR by StreamManager.", radioSlugForLog));
            }
        } else {
            radioSlugForLog = "UNKNOWN_STATION";
            LOGGER.error("StreamManager.initialize: RadioStation object is null. Cannot set initial status or properly initialize.");
            // Early exit or throw exception if radioStation is essential for further initialization
            return;
        }

        LOGGER.info("New broadcast initialized for {}", radioSlugForLog);

        playlistManager = new PlaylistManager(this);
        if (radioStation.getManagedBy() == ManagedBy.ITSELF || radioStation.getManagedBy() == ManagedBy.MIX) {
            playlistManager.startSelfManaging();
        }

        Cancellable feeder = segmentFeederTimer.getTicker().subscribe().with(
                timestamp -> executorService.submit(this::feedSegments),
                error -> LOGGER.error("Feeder subscription error for {}: {}", radioSlugForLog, error.getMessage(), error)
        );

        Cancellable slider = sliderTimer.getTicker().subscribe().with(
                timestamp -> executorService.submit(this::slideWindow),
                error -> LOGGER.error("Slider subscription error for {}: {}", radioSlugForLog, error.getMessage(), error)
        );

        timerSubscriptions.put("feeder", feeder);
        timerSubscriptions.put("slider", slider);
    }

    private void feedSegments() {
        String radioSlugForDebug = (this.radioStation != null && this.radioStation.getSlugName() != null)
                ? this.radioStation.getSlugName()
                : "UNKNOWN_STATION";
        System.out.println(String.format("feedSegments Debug: [START] For %s. liveSegments: %d, pendingQueue: %d.",
                radioSlugForDebug, liveSegments.size(), pendingFragmentSegmentsQueue.size()));

        int drippedCountThisCall = 0;
        if (pendingFragmentSegmentsQueue.isEmpty()) {
            System.out.println(String.format("feedSegments Debug: [DRIP] Pending queue for %s is empty. No segments to drip to liveSegments this pass.", radioSlugForDebug));
        } else {
            System.out.println(String.format("feedSegments Debug: [DRIP] For %s. Attempting to drip up to %d segment(s) from pendingQueue (current size %d) to liveSegments (current size %d, drip limit if full %d).",
                    radioSlugForDebug, SEGMENTS_TO_DRIP_PER_FEED_CALL, pendingFragmentSegmentsQueue.size(), liveSegments.size(), maxVisibleSegments * 2));
            for (int i = 0; i < SEGMENTS_TO_DRIP_PER_FEED_CALL; i++) {
                if (liveSegments.size() >= maxVisibleSegments * 2) {
                    System.out.println(String.format("feedSegments Debug: [DRIP] liveSegments buffer for %s is full or at limit (%d/%d). Pausing drip-feed for this call.",
                            liveSegments.size(), maxVisibleSegments * 2, radioSlugForDebug));
                    break;
                }
                if (!pendingFragmentSegmentsQueue.isEmpty()) {
                    HlsSegment segmentToMakeLive = pendingFragmentSegmentsQueue.poll();
                    liveSegments.put(segmentToMakeLive.getSequence(), segmentToMakeLive);
                    drippedCountThisCall++;
                    System.out.println(String.format("feedSegments Debug: [DRIP] Dripped segment %d to liveSegments for %s. liveSegments now: %d, pendingQueue now: %d",
                            segmentToMakeLive.getSequence(), radioSlugForDebug, liveSegments.size(), pendingFragmentSegmentsQueue.size()));
                } else {
                    System.out.println(String.format("feedSegments Debug: [DRIP] pendingFragmentSegmentsQueue for %s became empty during drip attempt.", radioSlugForDebug));
                    break;
                }
            }
            if (drippedCountThisCall > 0) {
                System.out.println(String.format("feedSegments Debug: [DRIP] Finished dripping for %s. Dripped %d segment(s) in this call.", radioSlugForDebug, drippedCountThisCall));
                if (radioStation.getStatus() != RadioStationStatus.ON_LINE && !liveSegments.isEmpty()) { // Check liveSegments not empty
                    radioStation.setStatus(RadioStationStatus.ON_LINE);
                    System.out.println(String.format("feedSegments Debug: [STATUS] Radio station %s status set to ON_LINE.", radioSlugForDebug));
                }
            } else {
                System.out.println(String.format("feedSegments Debug: [DRIP] No segments were dripped for %s in this call (e.g., liveSegments full or pending became empty before drip).", radioSlugForDebug));
            }
        }

        if (pendingFragmentSegmentsQueue.size() < PENDING_QUEUE_REFILL_THRESHOLD) {
            System.out.println(String.format("feedSegments Debug: [REFILL] Pending queue for %s is low (size: %d, threshold: %d). Attempting to fetch new fragment.",
                    radioSlugForDebug, pendingFragmentSegmentsQueue.size(), PENDING_QUEUE_REFILL_THRESHOLD));
            try {
                BrandSoundFragment fragment = playlistManager.getNextFragment();
                if (fragment != null && !fragment.getSegments().isEmpty()) {
                    int newSegmentsFromFragment = fragment.getSegments().size();
                    final long[] firstSeqInBatch = {-1L};
                    final long[] lastSeqInBatch = {-1L};

                    System.out.println(String.format("feedSegments Debug: [REFILL] Fetched BrandSoundFragment for %s with %d HLS segments. Assigning sequence numbers...",
                            radioSlugForDebug, newSegmentsFromFragment));
                    fragment.getSegments().forEach(segment -> {
                        long seq = currentSequence.getAndIncrement();
                        if (firstSeqInBatch[0] == -1L) {
                            firstSeqInBatch[0] = seq;
                        }
                        lastSeqInBatch[0] = seq;
                        segment.setSequence(seq);
                        pendingFragmentSegmentsQueue.offer(segment);
                    });
                    System.out.println(String.format("feedSegments Debug: [REFILL] Added %d new segments (sequences approx %d to %d) to pendingFragmentSegmentsQueue for %s. New pendingQueue size: %d",
                            newSegmentsFromFragment, firstSeqInBatch[0], lastSeqInBatch[0], radioSlugForDebug, pendingFragmentSegmentsQueue.size()));
                } else {
                    System.out.println(String.format("feedSegments Debug: [REFILL] No new fragment or empty fragment obtained for %s from PlaylistManager.", radioSlugForDebug));
                }
            } catch (Exception e) {
                LOGGER.error("feedSegments: Error during [REFILL] fetching/processing new fragment for {}: {}", radioSlugForDebug, e.getMessage(), e);
            }
        } else {
            System.out.println(String.format("feedSegments Debug: [REFILL] Pending queue for %s not low (size: %d, threshold: %d). No refill attempt needed.",
                    radioSlugForDebug, pendingFragmentSegmentsQueue.size(), PENDING_QUEUE_REFILL_THRESHOLD));
        }
        System.out.println(String.format("feedSegments Debug: [END] For %s. liveSegments: %d, pendingQueue: %d",
                radioSlugForDebug, liveSegments.size(), pendingFragmentSegmentsQueue.size()));
    }

    private void slideWindow() {
        String radioSlugForDebug = (this.radioStation != null && this.radioStation.getSlugName() != null)
                ? this.radioStation.getSlugName()
                : "UNKNOWN_STATION";
        if (liveSegments.isEmpty()) {
            return;
        }
        System.out.println(String.format("slideWindow Debug: Checking window for %s. Current segments in liveSegments: %d, Max visible in playlist: %d",
                radioSlugForDebug, liveSegments.size(), maxVisibleSegments));
        int removedCount = 0;
        while (liveSegments.size() > maxVisibleSegments) {
            long removedKey = liveSegments.firstKey();
            liveSegments.pollFirstEntry();
            removedCount++;
            System.out.println(String.format("slideWindow Debug: Removed segment for %s with sequence %d. liveSegments now: %d",
                    radioSlugForDebug, removedKey, liveSegments.size()));
        }
        if (removedCount > 0) {
            System.out.println(String.format("slideWindow Debug: Finished sliding for %s. Removed %d segment(s). liveSegments now: %d. First key in liveSegments: %s",
                    radioSlugForDebug, removedCount, liveSegments.size(), liveSegments.isEmpty() ? "N/A" : liveSegments.firstKey()));
        } else {
            System.out.println(String.format("slideWindow Debug: No segments needed removal for %s during this pass. liveSegments size: %d. First key: %s",
                    radioSlugForDebug, liveSegments.size(), liveSegments.isEmpty() ? "N/A" : liveSegments.firstKey()));
        }
    }

    @Override
    public String generatePlaylist() {
        String radioSlugForDebug = (this.radioStation != null && this.radioStation.getSlugName() != null)
                ? this.radioStation.getSlugName()
                : "UNKNOWN_STATION";

        if (liveSegments.isEmpty()) {
            System.out.println(String.format("generatePlaylist Debug: Playlist for %s: MEDIA-SEQUENCE=0, Segments=[] (Live segments empty)", radioSlugForDebug));
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

        List<Long> includedSegmentSequences = new ArrayList<>();
        String currentRadioSlugForPath = (this.radioStation != null && this.radioStation.getSlugName() != null)
                ? this.radioStation.getSlugName() : "default_station_path";


        liveSegments.tailMap(firstSequenceInWindow).entrySet().stream()
                .limit(maxVisibleSegments)
                .forEach(entry -> {
                    HlsSegment segment = entry.getValue();
                    includedSegmentSequences.add(segment.getSequence());
                    playlist.append("#EXTINF:")
                            .append(segment.getDuration())
                            .append(",")
                            .append(segment.getSongName())
                            .append("\n")
                            .append("segments/")
                            .append(currentRadioSlugForPath)
                            .append("_")
                            .append(segment.getSequence())
                            .append(".ts\n");
                });

        String segmentsLogString = includedSegmentSequences.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(", "));

        System.out.println(String.format("generatePlaylist Debug: Playlist for %s: MEDIA-SEQUENCE=%d, Segments=[%s]",
                radioSlugForDebug, firstSequenceInWindow, segmentsLogString));

        return playlist.toString();
    }

    @Override
    public HlsSegment getSegment(String segmentParam) {
        String radioSlugForDebug = (this.radioStation != null && this.radioStation.getSlugName() != null)
                ? this.radioStation.getSlugName() : "UNKNOWN_STATION";
        try {
            Matcher matcher = SEGMENT_PATTERN.matcher(segmentParam);
            if (!matcher.find()) {
                LOGGER.warn("Segment '{}' doesn't match expected pattern: {} for station: {}", segmentParam, SEGMENT_PATTERN.pattern(), radioSlugForDebug);
                return null;
            }
            long segmentSequence = Long.parseLong(matcher.group(2));
            latestRequestedSegment = segmentSequence;
            HlsSegment segment = liveSegments.get(segmentSequence);
            if (segment == null) {
                LOGGER.debug("Segment {} not found in liveSegments for {}", segmentSequence, radioSlugForDebug);
            }
            return segment;
        } catch (Exception e) {
            LOGGER.warn("Error processing segment request '{}' for station {}: {}", segmentParam, radioSlugForDebug, e.getMessage(), e);
            return null;
        }
    }

    @Override
    public long getLatestRequestedSeg() {
        return latestRequestedSegment;
    }

    @Override
    public void shutdown() {
        String radioSlugForDebug = (this.radioStation != null && this.radioStation.getSlugName() != null)
                ? this.radioStation.getSlugName() : "UNKNOWN_STATION";
        LOGGER.info("Shutting down HLSPlaylist for: {}", radioSlugForDebug);
        timerSubscriptions.forEach((key, subscription) -> {
            if (subscription != null) subscription.cancel();
        });
        timerSubscriptions.clear();
        executorService.shutdownNow();
        currentSequence.set(0);
        liveSegments.clear();
        pendingFragmentSegmentsQueue.clear(); // Clear the new queue as well
        LOGGER.info("HLSPlaylist for {} has been shut down. All queues cleared.", radioSlugForDebug);
        if (radioStation != null) {
            radioStation.setStatus(RadioStationStatus.OFF_LINE); // Also update local status
        }
    }
}