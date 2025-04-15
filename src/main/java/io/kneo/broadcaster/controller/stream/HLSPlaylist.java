package io.kneo.broadcaster.controller.stream;

import io.kneo.broadcaster.config.HlsPlaylistConfig;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.model.BrandSoundFragment;
import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.service.AudioSegmentationService;
import io.kneo.broadcaster.service.SoundFragmentService;
import io.kneo.broadcaster.service.radio.PlaylistManager;
import io.kneo.broadcaster.service.stream.SegmentFeederTimer;
import io.kneo.broadcaster.service.stream.SegmentJanitorTimer;
import io.kneo.broadcaster.service.stream.SliderTimer;
import io.smallrye.mutiny.subscription.Cancellable;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HLSPlaylist {
    private static final ZoneId LISBON_ZONE = ZoneId.of("Europe/Lisbon");
    private static final Logger LOGGER = LoggerFactory.getLogger(HLSPlaylist.class);
    private static final Pattern SEGMENT_PATTERN = Pattern.compile("([^_]+)_([0-9]+)_([0-9]+)\\.ts$");
    @Getter
    private final Map<Integer, PlaylistFragmentRange> mainQueue = Collections.synchronizedMap(new LinkedHashMap<>());
    @Getter
    private final KeySet keySet = new KeySet();
    private final AtomicInteger rangeCounter = new AtomicInteger(0);
    private final AtomicLong currentSequence = new AtomicLong(0);
    private long latestRequestedSegment = 0;
    private final Map<String, AtomicBoolean> processingFlags = new ConcurrentHashMap<>();
    private final AtomicBoolean windowSliderProcessingFlag = new AtomicBoolean(false);
    @Getter
    private ZonedDateTime lastSlide;
    @Getter
    private final Map<String, Cancellable> timerSubscriptions = new ConcurrentHashMap<>();
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
    private SliderTimer sliderTimer;
    @Getter
    private SegmentFeederTimer segmentFeederTimer;
    @Getter
    private final SegmentJanitorTimer janitorTimer;
    @Getter
    private HLSPlaylistStats stats;
    private final ExecutorService slideExecutor = Executors.newSingleThreadExecutor();
    private ZonedDateTime currentSegmentEndTime = ZonedDateTime.now(LISBON_ZONE)
            .plusMinutes(3);
    private final Deque<SlideEvent> slideHistory = new ArrayDeque<>(20);
    private final AtomicLong slideSequence = new AtomicLong(0);

    public HLSPlaylist(
            SliderTimer sliderTimer,
            SegmentFeederTimer segmentFeederTimer,
            SegmentJanitorTimer janitorTimer,
            HlsPlaylistConfig config,
            SoundFragmentService soundFragmentService,
            AudioSegmentationService segmentationService,
            String brandName) {
        this.sliderTimer = sliderTimer;
        this.segmentFeederTimer = segmentFeederTimer;
        this.janitorTimer = janitorTimer;
        this.config = config;
        this.brandName = brandName;
        this.soundFragmentService = soundFragmentService;
        this.segmentationService = segmentationService;
        stats = new HLSPlaylistStats(mainQueue);
        LOGGER.info("Created HLSPlaylist for brand: {}", brandName);
    }

    public void initialize() {
        LOGGER.info("New broadcast initialized for {}", brandName);
        playlistManager = new PlaylistManager(this);
        playlistManager.start();
        LOGGER.info("Initializing maintenance for playlist: {}", brandName);
        Cancellable feeder = segmentFeederTimer.getTicker().subscribe().with(
                timestamp -> {
                    LOGGER.debug("Feeder tick: {}", timestamp);
                    feed();
                },
                error -> LOGGER.error("Feeder subscription error {}", error.getMessage())
        );
        Cancellable slider = sliderTimer.getTicker().subscribe().with(
                ts -> slide(SlideType.SCHEDULED_TIMER),
                error -> LOGGER.error("Timer error", error)
        );
        Cancellable janitor = janitorTimer.getTicker().subscribe().with(
                timestamp -> {
                    LOGGER.debug("Janitor tick: {}", timestamp);
                    clean(2);
                },
                error -> LOGGER.error("Janitor subscription error {}", error.getMessage())
        );
        timerSubscriptions.put("feeder", feeder);
        timerSubscriptions.put("slider", slider);
        timerSubscriptions.put("janitor", janitor);
    }

    public String generatePlaylist() {
        String programDateTime = getFormattedDateTime();
        PlaylistFragmentRange currentRange = mainQueue.get(keySet.current());
        PlaylistFragmentRange nextRange = mainQueue.get(keySet.next());
        PlaylistFragmentRange futureRange = mainQueue.get(keySet.future());

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
                .append("#EXT-X-MEDIA-SEQUENCE:").append(currentRange.getStart()).append("\n")
                .append("#EXT-X-PROGRAM-DATE-TIME:").append(programDateTime).append("\n");

        appendSegments(playlist, currentRange, keySet.current());

        if (nextRange != null) {
            appendSegments(playlist, nextRange, keySet.next());
        }

        if (futureRange != null) {
            appendSegments(playlist, futureRange, keySet.future());
        }

        return playlist.toString();
    }


    public HlsSegment getSegment(String segmentParam) {
        try {
            Matcher matcher = SEGMENT_PATTERN.matcher(segmentParam);
            if (!matcher.find()) {
                LOGGER.warn("Segment doesn't match pattern: {}", segmentParam);
                return null;
            }
            String fragmentIdStr = matcher.group(2);
            int fragmentId = Integer.parseInt(fragmentIdStr);
            latestRequestedSegment = Long.parseLong(matcher.group(3));

            PlaylistFragmentRange range = mainQueue.get(fragmentId);

            long segmentsRemaining = range.getSegments().lastKey() - latestRequestedSegment;
            if (segmentsRemaining <= 1) {
                LOGGER.warn("Player starving! Only {} segments left. Triggering emergency slide.", segmentsRemaining);
                slideExecutor.execute(() -> slide(SlideType.PLAYER_STARVATION));
            }

            HlsSegment segment = range.getSegments().get(latestRequestedSegment);
            if (segment != null) {
                stats.setLastRequestedSegment(range.getFragment().getTitle());
            } else {
                LOGGER.debug("Segment {} not found in fragment {}", latestRequestedSegment, fragmentIdStr);
            }
            return segment;

        } catch (Exception e) {
            LOGGER.warn("Error processing segment request '{}': {}", segmentParam, e.getMessage());
            return null;
        }
    }

    public long getLatestRequestedSeg() {
        return latestRequestedSegment;
    }

    public void shutdown() {
        LOGGER.info("Shutting down playlist for: {}", brandName);
        timerSubscriptions.forEach((key, subscription) -> { // Updated key name for clarity
            if (subscription != null) subscription.cancel();
        });
        timerSubscriptions.clear();
        currentSequence.set(0);
    }

    public void manualSlide() {
        slide(SlideType.MANUAL_OVERRIDE);
    }

    private void appendSegments(StringBuilder playlist, PlaylistFragmentRange range, int rangeKey) {
        range.getSegments().forEach((seqKey, segment) -> {
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
        });
    }

    private void feed() {
        if (!processingFlags.computeIfAbsent(brandName, k -> new AtomicBoolean(false))
                .compareAndSet(false, true)) {
            return;
        }


        try {
            if (mainQueue.size() < 10) {
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
                        mainQueue.put(rangeCounter.getAndIncrement(),
                                new PlaylistFragmentRange(newSegments, firstSequence, lastSequence, fragment.getSoundFragment()));
                        LOGGER.debug("Added fragment for brand {}: {}", brandName, fragment.getSoundFragment().getMetadata());
                        radioStation.setStatus(RadioStationStatus.ON_LINE);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Processing error for brand {}: {}", brandName, e.getMessage(), e);
        } finally {
            processingFlags.get(brandName).set(false);
        }
    }

    private void clean(int staleKeysToKeep) {
        // Assumes mainQueue is a Map<Integer, PlaylistFragmentRange> accessible in the scope
        // Assumes PlaylistFragmentRange has a boolean isStale() method

        PriorityQueue<Integer> newestStaleKeysHeap = null; // Init outside sync block if needed later, but populated inside
        Set<Integer> staleKeysToRemove = null; // Init outside sync block if needed later, but populated inside

        synchronized (mainQueue) { // Synchronize the entire operation if mainQueue is shared
            if (mainQueue.isEmpty() || staleKeysToKeep < 0) {
                return;
            }
            newestStaleKeysHeap = new PriorityQueue<>(); // Initialize inside sync block
            staleKeysToRemove = new HashSet<>(); // Initialize inside sync block

            for (Map.Entry<Integer, PlaylistFragmentRange> entry : mainQueue.entrySet()) {
                if (entry.getValue().isStale()) {
                    if (staleKeysToKeep == 0) {
                        staleKeysToRemove.add(entry.getKey());
                    } else {
                        if (newestStaleKeysHeap.size() < staleKeysToKeep) {
                            newestStaleKeysHeap.offer(entry.getKey());
                        } else if (entry.getKey() > newestStaleKeysHeap.peek()) {
                            staleKeysToRemove.add(newestStaleKeysHeap.poll());
                            newestStaleKeysHeap.offer(entry.getKey());
                        } else {
                            staleKeysToRemove.add(entry.getKey());
                        }
                    }
                }
            }

            // Removal part is already covered by the synchronized block
            staleKeysToRemove.forEach(key -> {
                mainQueue.remove(key);
            });
        }
    }

    private void slide(SlideType slideType) {
        if (!windowSliderProcessingFlag.compareAndSet(false, true)) {
            return;
        }
        try {

            long currentSeg = currentSequence.get();
            long ls = getLastSegInRange();
            if (ls >= currentSeg) {
                return;
            }


            ZonedDateTime now = ZonedDateTime.now(LISBON_ZONE);
            if (slideType == SlideType.PLAYER_STARVATION) {
                doSlide(now, slideType, Duration.ZERO);
                return;
            }

            Duration timingError = currentSegmentEndTime != null
                    ? Duration.between(currentSegmentEndTime, now)
                    : Duration.ZERO;

            // - First time (null end time)
            // - Within 5 second window (early/late)
            // - Or extremely late (>5 sec)
            if (currentSegmentEndTime == null ||
                    timingError.abs().getSeconds() <= 5 ||
                    timingError.toMinutes() > 1) {
                doSlide(now, slideType, timingError);
            } else {
                LOGGER.warn("Slide skipped - {}ms remaining",
                        timingError.negated().toMillis());
            }

        } finally {
            windowSliderProcessingFlag.set(false);
        }
    }

    private long getLastSegInRange() {
        PlaylistFragmentRange range =  mainQueue.get(keySet.future());
        if (range != null) {
            return range.getEnd() - 1;
        } else {
            PlaylistFragmentRange range2 =  mainQueue.get(keySet.next());
            if (range2 != null) {
                return range2.getEnd() - 1;
            } else {
                return currentSequence.get() + 100;
            }

        }

    }

    private void doSlide(ZonedDateTime now, SlideType slideType, Duration timingError) {
        if (slideType != SlideType.PLAYER_STARVATION &&
                !mainQueue.isEmpty() &&
                keySet.current() == Collections.max(mainQueue.keySet())) {
            LOGGER.debug("Already showing newest fragment {}", keySet.current());
            return;
        }

        // Get current state
        int currentKey = keySet.current();
        int nextKey = keySet.next();
        int futureKey = keySet.future();

        // Simplified fragment access
        PlaylistFragmentRange current = mainQueue.get((long) currentKey);
        PlaylistFragmentRange next = mainQueue.get((long) nextKey);
        PlaylistFragmentRange future = mainQueue.get((long) futureKey);

        // Create event with proper null checks
        addToHistory(new SlideEvent(
                slideType,
                now,
                slideSequence.incrementAndGet(),
                timingError,
                currentKey,
                current != null ? current.getStart() : -1,
                current != null ? current.getEnd() : -1,
                current != null ? current.getFragment().getId().toString() : "N/A",
                now,
                nextKey,
                next != null ? next.getStart() : -1,
                next != null ? next.getEnd() : -1,
                next != null ? next.getFragment().getId().toString() : "N/A",
                futureKey,
                future != null ? future.getStart() : -1,
                future != null ? future.getEnd() : -1,
                future != null ? future.getFragment().getId().toString() : "N/A"
        ));

        // Mark current as stale if exists
        if (current != null) {
            current.setStale(true);
        }

        // Perform slide
        keySet.slide();
        currentSegmentEndTime = now.plusSeconds(config.getSegmentDuration());
        lastSlide = now;

        // Log actual values that were stored
        LOGGER.info("Slide executed: {} ({}ms {}) Current: {} [{}] Next: {} [{}] Future: {} [{}]",
                slideType,
                timingError.abs().toMillis(),
                timingError.isNegative() ? "early" : "late",
                currentKey,
                current != null ? current.getFragment().getId() : "MISSING",
                nextKey,
                next != null ? next.getFragment().getId() : "MISSING",
                futureKey,
                future != null ? future.getFragment().getId() : "MISSING"
        );
    }

    private void addToHistory(SlideEvent event) {
        synchronized (slideHistory) {
            slideHistory.addLast(event);
            if (slideHistory.size() > 20) {
                slideHistory.removeFirst();
            }
        }
    }

    public List<SlideEvent> getSlideHistory() {
        synchronized (slideHistory) {
            return new ArrayList<>(slideHistory);
        }
    }

    private String getFormattedDateTime() {
        return (lastSlide != null ? lastSlide : ZonedDateTime.now(LISBON_ZONE))
                .format(DateTimeFormatter.ISO_INSTANT);
    }

    public List<Long[]> getCurrentWindow() {
        try {
            return List.of(
                    extractRange(keySet.current()),
                    extractRange(keySet.next()),
                    extractRange(keySet.future())
            );
        } catch (Exception e) {
            return List.of(new Long[0], new Long[0], new Long[0]);
        }
    }

    private Long[] extractRange(int key) {
        try {
            return new Long[]{mainQueue.get(key).getStart(), mainQueue.get(key).getEnd()};
        } catch (Exception e) {
            return new Long[]{0L, 0L};
        }
    }

}