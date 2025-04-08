package io.kneo.broadcaster.controller.stream;

import io.kneo.broadcaster.config.HlsPlaylistConfig;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.model.BrandSoundFragment;
import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.service.AudioSegmentationService;
import io.kneo.broadcaster.service.SoundFragmentService;
import io.kneo.broadcaster.service.radio.PlaylistManager;
import io.kneo.broadcaster.service.stream.TimerService;
import io.kneo.broadcaster.service.stream.WindowSliderService;
import io.smallrye.mutiny.subscription.Cancellable;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class HLSPlaylist {
    private static final Logger LOGGER = LoggerFactory.getLogger(HLSPlaylist.class);
    private static final Pattern SEGMENT_PATTERN = Pattern.compile("([^_]+)_([0-9]+)_([0-9]+)\\.ts$");
    private final Map<Integer, PlaylistRange> mainQueue = Collections.synchronizedMap(new LinkedHashMap<>());
    private final PlaylistRangeKeySet playlistRangeKeySet = new PlaylistRangeKeySet();
    private final AtomicInteger rangeCounter = new AtomicInteger(0);
    private final AtomicLong currentSequence = new AtomicLong(0);
    private final AtomicLong totalBytesProcessed = new AtomicLong(0);

    private final Map<String, AtomicBoolean> processingFlags = new ConcurrentHashMap<>();
    private final AtomicBoolean windowSliderProcessingFlag = new AtomicBoolean(false);
    private final Map<String, Cancellable> timerSubscriptions = new ConcurrentHashMap<>();
    private final AtomicInteger currentDuration = new AtomicInteger(0);

    @Getter
    private final List<Integer> segmentSizeHistory = new CopyOnWriteArrayList<>();
    private final int HISTORY_MAX_SIZE = 60;

    @Setter
    private RadioStation radioStation;

    @Getter
    @Setter
    private String brandName;
    @Getter
    private final SoundFragmentService soundFragmentService;
    @Getter
    private PlaylistManager playlistManager;
    @Getter
    private final AudioSegmentationService segmentationService;
    @Getter
    private final HlsPlaylistConfig config;
    @Getter
    private TimerService timerService;
    private final WindowSliderService windowSliderService;
    @Getter
    private HlsSegmentStats stats;

    public HLSPlaylist(
            TimerService timerService,
            WindowSliderService windowSliderService,
            HlsPlaylistConfig config,
            SoundFragmentService soundFragmentService,
            AudioSegmentationService segmentationService,
            String brandName) {
        this.timerService = timerService;
        this.windowSliderService = windowSliderService;
        this.config = config;
        this.brandName = brandName;
        this.soundFragmentService = soundFragmentService;
        this.segmentationService = segmentationService;
        stats = new HlsSegmentStats(mainQueue);
        playlistRangeKeySet.slide();
        playlistRangeKeySet.slide();
        LOGGER.info("Created HLSPlaylist for brand: {}", brandName);
    }

    public void initialize() {
        LOGGER.info("New broadcast initialized for {}", brandName);
        playlistManager = new PlaylistManager(this);
        playlistManager.start();
        LOGGER.info("Initializing maintenance for playlist: {}", brandName);
        Cancellable subscription = timerService.getTicker().subscribe().with(
                timestamp -> processTimerTick(brandName, timestamp),
                error -> LOGGER.error("Timer subscription error for brand {}: {}", brandName, error.getMessage())
        );
        Cancellable ws = windowSliderService.getSliderTicker().subscribe().with(
                timestamp -> {
                    LOGGER.debug("Slider tick: {}", timestamp);
                    windowSlider();
                },
                error -> LOGGER.error("Slider error", error)
        );

        timerSubscriptions.put(brandName + "-slider", ws);
        timerSubscriptions.put(brandName, subscription);
    }



    private void processTimerTick(String brandName, long timestamp) {
        if (!processingFlags.computeIfAbsent(brandName, k -> new AtomicBoolean(false))
                .compareAndSet(false, true)) {
            return;
        }

        try {
            BrandSoundFragment fragment = playlistManager.getNextFragment();
            if (fragment != null) {
                ConcurrentNavigableMap<Long, HlsSegment> newSegments = new ConcurrentSkipListMap<>();
                long firstSequence = currentSequence.get();

                fragment.getSegments().forEach(segment -> {
                    long sequence = currentSequence.getAndIncrement();
                    segment.setSequence(sequence);
                    newSegments.put(sequence, segment);
                });

                if (!newSegments.isEmpty()) {
                    long lastSequence = currentSequence.get() - 1;
                    mainQueue.put(rangeCounter.getAndIncrement(), new PlaylistRange(newSegments, firstSequence, lastSequence, fragment.getSoundFragment()));
                    LOGGER.debug("Added fragment: {}", fragment.getSoundFragment().getMetadata());
                }
            }

        } catch (Exception e) {
            LOGGER.error("Processing error for {}: {}", brandName, e.getMessage(), e);
        } finally {
            processingFlags.get(brandName).set(false);
        }
    }

    private void windowSlider() {
        if (!windowSliderProcessingFlag.compareAndSet(false, true)) {
            return;
        }

        try {
            int duration = getTotalSegmentsDuration();
            currentDuration.set(duration);
            windowSliderService.updateSlideDelay(duration * 1000L);
            mainQueue.remove(playlistRangeKeySet.current());
            playlistRangeKeySet.slide();
            LOGGER.debug("Window slid. Next slide in {} seconds", duration);
        } catch (Exception e) {
            LOGGER.error("Processing error for {}: {}", brandName, e.getMessage(), e);
        } finally {
            windowSliderProcessingFlag.set(false);
        }
    }



    private void recordSegmentSize(int size) {
        if (segmentSizeHistory.size() >= HISTORY_MAX_SIZE) {
            segmentSizeHistory.remove(0);
        }
        segmentSizeHistory.add(size);
    }

    public String generatePlaylist() {
        radioStation.setStatus(RadioStationStatus.ON_LINE);

        PlaylistRange currentRange = mainQueue.get(playlistRangeKeySet.current());
        PlaylistRange nextRange = mainQueue.get(playlistRangeKeySet.next());
        PlaylistRange futureRange = playlistRangeKeySet.windowSize() > 2 ?
                mainQueue.get(playlistRangeKeySet.future()) : null;

        if (currentRange == null) {
            return "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-ALLOW-CACHE:NO\n#EXT-X-TARGETDURATION:" +
                    config.getSegmentDuration() + "\n#EXT-X-MEDIA-SEQUENCE:" + currentSequence.get() +
                    "\n#EXT-X-DISCONTINUITY-SEQUENCE:" + (currentSequence.get() / 1000) + "\n";
        }

        StringBuilder playlist = new StringBuilder();
        playlist.append("#EXTM3U\n")
                .append("#EXT-X-VERSION:3\n")
                .append("#EXT-X-ALLOW-CACHE:NO\n")
                .append("#EXT-X-PLAYLIST-TYPE:EVENT\n")
                .append("#EXT-X-START:TIME-OFFSET=0,PRECISE=YES\n")
                .append("#EXT-X-TARGETDURATION:").append(config.getSegmentDuration()).append("\n")
                .append("#EXT-X-STREAM-INF:BANDWIDTH=64000\n")
                .append("#EXT-X-MEDIA-SEQUENCE:").append(currentRange.start()).append("\n")
                .append("#EXT-X-PROGRAM-DATE-TIME:").append(getFormattedDateTime()).append("\n");

        appendSegments(playlist, currentRange, playlistRangeKeySet.current());

        if (nextRange != null) {
            playlist.append("#EXT-X-DISCONTINUITY\n");
            appendSegments(playlist, nextRange, playlistRangeKeySet.next());
            if (futureRange != null) {
                playlist.append("#EXT-X-DISCONTINUITY\n");
                appendSegments(playlist, futureRange, playlistRangeKeySet.future());
            }
        }
        return playlist.toString();
    }

    private void appendSegments(StringBuilder playlist, PlaylistRange range, int rangeKey) {
        range.segments().forEach((seqKey, segment) -> {
            playlist.append("#EXTINF:")
                    .append(segment.getDuration())
                    .append(",")
                    .append(segment.getSongName())
                    .append("\n")
                    .append("segments/")
                    .append(brandName)
                    .append("_")
                    .append(rangeKey)
                    .append("_")
                    .append(seqKey)
                    .append(".ts\n");
            recordSegmentSize(range.segments().size());
        });
    }

    public HlsSegment getSegment(String segmentParam) {
        try {
            Matcher matcher = SEGMENT_PATTERN.matcher(segmentParam);
            if (!matcher.find()) {
                LOGGER.warn("Segment doesn't match pattern: {}", segmentParam);
                return null;
            }
            String fragmentIdStr = matcher.group(2);
            long sequence = Long.parseLong(matcher.group(3));

            PlaylistRange range = mainQueue.get(Integer.parseInt(fragmentIdStr));
            HlsSegment segment = range.segments().get(sequence);
            if (segment != null) {
                stats.setLastRequestedSegment(range.fragment().getTitle());
            } else {
                LOGGER.debug("Segment {} not found in fragment {}", sequence, fragmentIdStr);
            }
            return segment;

        } catch (IllegalArgumentException e) {
            LOGGER.warn("Malformed segment request: {}", segmentParam);
            return null;
        }
    }

    public int getSegmentCount() {
        return 777;
    }

    public int getQueueSize() {
        return mainQueue.size();
    }

    public long getTotalBytesProcessed() {
        return totalBytesProcessed.get();
    }

    public void shutdown() {
        LOGGER.info("Shutting down playlist for: {}", brandName);
        timerSubscriptions.forEach((brand, subscription) -> {
            if (subscription != null) subscription.cancel();
        });
        timerSubscriptions.clear();
        currentSequence.set(0);
        totalBytesProcessed.set(0);
        segmentSizeHistory.clear();
    }

    private int getTotalSegmentsDuration() {
        // Get all available ranges first
        PlaylistRange current = mainQueue.get(playlistRangeKeySet.current());
        PlaylistRange next = mainQueue.get(playlistRangeKeySet.next());
        PlaylistRange future = playlistRangeKeySet.windowSize() > 2 ?
                mainQueue.get(playlistRangeKeySet.future()) : null;

        // Create a stream of non-null ranges
        return Stream.of(current)
                .filter(Objects::nonNull)
                .filter(range -> range.segments() != null) // Additional null check
                .flatMap(range -> range.segments().values().stream())
                .filter(Objects::nonNull) // Filter out null segments
                .mapToInt(HlsSegment::getDuration)
                .sum();
    }

    private String getFormattedDateTime() {
        return java.time.Instant.now()
                .atZone(java.time.ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.ISO_INSTANT);
    }
}