package io.kneo.broadcaster.controller.stream;

import io.kneo.broadcaster.config.HlsPlaylistConfig;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.model.BrandSoundFragment;
import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.model.cnst.ManagedBy;
import io.kneo.broadcaster.service.manipulation.AudioSegmentationService;
import io.kneo.broadcaster.service.SoundFragmentService;
import io.kneo.broadcaster.service.radio.PlaylistManager;
import io.kneo.broadcaster.service.stream.SegmentFeederTimer;
import io.kneo.broadcaster.service.stream.SegmentJanitorTimer;
import io.kneo.broadcaster.service.stream.SliderTimer;
import io.smallrye.mutiny.subscription.Cancellable;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HLSPlaylist {
    private static final ZoneId ZONE_ID = ZoneId.of("Europe/Lisbon");
    private static final Logger LOGGER = LoggerFactory.getLogger(HLSPlaylist.class);
    private static final Pattern SEGMENT_PATTERN = Pattern.compile("([^_]+)_([0-9]+)_([0-9]+)\\.ts$");
    private static final int SHORTEN_LAST_SLIDING_SEC = 60;
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
    @Getter
    private RadioStation radioStation;
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
    private final Deque<SlideEvent> slideHistory = new ArrayDeque<>(20);
    private final AtomicLong slideSequence = new AtomicLong(0);

    public HLSPlaylist(
            SliderTimer sliderTimer,
            SegmentFeederTimer segmentFeederTimer,
            SegmentJanitorTimer janitorTimer,
            HlsPlaylistConfig config,
            SoundFragmentService soundFragmentService,
            AudioSegmentationService segmentationService) {
        this.sliderTimer = sliderTimer;
        this.segmentFeederTimer = segmentFeederTimer;
        this.janitorTimer = janitorTimer;
        this.config = config;
        this.soundFragmentService = soundFragmentService;
        this.segmentationService = segmentationService;
        stats = new HLSPlaylistStats(mainQueue);
    }

    public void initialize() {
        LOGGER.info("New broadcast initialized for {}",  radioStation.getSlugName());
        playlistManager = new PlaylistManager(this);
        LOGGER.info("Initializing maintenance for playlist: {}",  radioStation.getSlugName());
        Cancellable feeder = segmentFeederTimer.getTicker().subscribe().with(
                timestamp -> {
                    LOGGER.debug("Feeder tick: {}", timestamp);
                    feed();
                },
                error -> LOGGER.error("Feeder subscription error {}", error.getMessage())
        );
        Cancellable slider = sliderTimer.getTicker().subscribe().with(
                ts -> slide(SlideType.SCHEDULED_TIMER, ts),
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

    public void setRadioStation(RadioStation radioStation) {
        if (radioStation.getManagedBy() == ManagedBy.ITSELF || radioStation.getManagedBy() == ManagedBy.MIX) {
            playlistManager.startSelfManaging();
            radioStation.setStatus(RadioStationStatus.WARMING_UP);
        } else {
            radioStation.setStatus(RadioStationStatus.WARMING_UP);
        }

        this.radioStation = radioStation;
    }

    public String generatePlaylist() {
        String programDateTime = getFormattedLastSlide();
        PlaylistFragmentRange currentRange = mainQueue.get(keySet.current());
        PlaylistFragmentRange nextRange = mainQueue.get(keySet.next());

        if (currentRange == null) {
            return "#EXTM3U\n" +
                    //"#EXT-X-VERSION:3\n" +
                    "#EXT-X-VERSION:1\n" +
                    "#EXT-X-ALLOW-CACHE:NO\n" +
                    "#EXT-X-TARGETDURATION:" + config.getSegmentDuration() + "\n" +
                    "#EXT-X-MEDIA-SEQUENCE:" + currentSequence.get() +
                    //"\n#EXT-X-DISCONTINUITY-SEQUENCE:" + (currentSequence.get() / 1000) +
                     "\n";
        }

        StringBuilder playlist = new StringBuilder();
        playlist.append("#EXTM3U\n")
                //.append("#EXT-X-VERSION:3\n")
                .append("#EXT-X-VERSION:1\n")
                .append("#EXT-X-ALLOW-CACHE:NO\n")
                .append("#EXT-X-PLAYLIST-TYPE:EVENT\n")
                //.append("#EXT-X-START:TIME-OFFSET=0,PRECISE=YES\n")
                .append("#EXT-X-TARGETDURATION:").append(config.getSegmentDuration()).append("\n")
                .append("#EXT-X-STREAM-INF:BANDWIDTH=64000\n")
                .append("#EXT-X-MEDIA-SEQUENCE:").append(currentRange.getStart()).append("\n");
                //.append("#EXT-X-PROGRAM-DATE-TIME:").append(programDateTime).append("\n");

        appendSegments(playlist, currentRange, keySet.current());

        if (nextRange != null) {
            appendSegments(playlist, nextRange, keySet.next());
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
                slideExecutor.execute(() -> slide(SlideType.PLAYER_STARVATION, 0));
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
        LOGGER.info("Shutting down playlist for: {}", radioStation.getSlugName());
        timerSubscriptions.forEach((key, subscription) -> {
            if (subscription != null) subscription.cancel();
        });
        timerSubscriptions.clear();
        currentSequence.set(0);
    }

    private void appendSegments(StringBuilder playlist, PlaylistFragmentRange range, int rangeKey) {
        range.getSegments().forEach((seqKey, segment) -> {
            playlist.append("#EXTINF:")
                    .append(segment.getDuration())
                    .append(",")
                    .append(segment.getSongName())
                    .append("\n")
                    .append("segments/")
                    .append(radioStation.getSlugName())
                    .append("_")
                    .append(rangeKey)
                    .append("_")
                    .append(seqKey)
                    .append(".ts\n");
        });
    }

    private void feed() {
        if (!processingFlags.computeIfAbsent(radioStation.getSlugName(), k -> new AtomicBoolean(false))
                .compareAndSet(false, true)) {
            return;
        }

        try {
            if (mainQueue.size() < 10) {
                BrandSoundFragment fragment = playlistManager.getNextFragment();
                if (fragment != null) {
                    ConcurrentNavigableMap<Long, HlsSegment> newSegments = new ConcurrentSkipListMap<>();
                    long firstSequence = currentSequence.get();
                    AtomicInteger duration = new AtomicInteger();
                    fragment.getSegments().forEach(segment -> {
                        long sequence = currentSequence.getAndIncrement();
                        segment.setSequence(sequence);
                        newSegments.put(sequence, segment);
                        duration.addAndGet(segment.getDuration());
                    });

                    if (!newSegments.isEmpty()) {
                        long lastSequence = currentSequence.get() - 1;
                        mainQueue.put(rangeCounter.getAndIncrement(),
                                new PlaylistFragmentRange(newSegments, firstSequence, lastSequence, duration.get(), fragment.getSoundFragment()));
                        LOGGER.debug("Added fragment for brand {}: {}", radioStation.getSlugName(), fragment.getSoundFragment().getMetadata());
                        radioStation.setStatus(RadioStationStatus.ON_LINE);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Processing error for brand {}: {}", radioStation.getSlugName(), e.getMessage(), e);
        } finally {
            processingFlags.get(radioStation.getSlugName()).set(false);
        }
    }

    private void clean(int staleKeysToKeep) {
        PriorityQueue<Integer> newestStaleKeysHeap;
        Set<Integer> staleKeysToRemove;

        synchronized (mainQueue) {
            if (mainQueue.isEmpty() || staleKeysToKeep < 0) {
                return;
            }
            newestStaleKeysHeap = new PriorityQueue<>();
            staleKeysToRemove = new HashSet<>();

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

            staleKeysToRemove.forEach(mainQueue::remove);
        }
    }

    private void slide(SlideType slideType, long timestamp) {
        if (!windowSliderProcessingFlag.compareAndSet(false, true)) {
            LOGGER.debug("Slide operation already in progress for {}, skipping.", radioStation.getSlugName());
            return;
        }
        try {
            ZonedDateTime now = ZonedDateTime.now(ZONE_ID);

            if (slideType == SlideType.PLAYER_STARVATION) {
                LOGGER.warn("Executing emergency slide due to PLAYER_STARVATION request.");
                //doSlide(now, slideType, Duration.ZERO);
                //lastSlide = now;
                lastSlide = lastSlide.minusSeconds(SHORTEN_LAST_SLIDING_SEC);
                return;
            }

            int currentKey = keySet.current();
            int nextKey = keySet.next();
            PlaylistFragmentRange current = mainQueue.get(currentKey);
            PlaylistFragmentRange next = mainQueue.get(nextKey);

            if (lastSlide == null) {
                Duration gap = Duration.ofSeconds(0);
                doSlide(now, slideType, gap, currentKey, nextKey, current, next);
                lastSlide = now;
            } else {
                //ZonedDateTime endOfTheLastFragment = lastSlide.plusSeconds(current.getDuration());
                ZonedDateTime endOfTheLastFragment = lastSlide.plusSeconds(next.getDuration());
                if (now.isAfter(endOfTheLastFragment)) {
                    Duration gap = Duration.between(endOfTheLastFragment, now);
                    doSlide(now, slideType, gap, currentKey, nextKey, current, next);
                    lastSlide = now;
                } else {
                    addToHistory(new SlideEvent(
                            SlideType.ESTIMATED,
                            endOfTheLastFragment,
                            slideSequence.incrementAndGet(),
                            Duration.ofSeconds(0),
                            currentKey,
                            current.getStart(),
                            current.getEnd(),
                            current.getFragment().getId().toString(),
                            endOfTheLastFragment,
                            nextKey,
                            next.getStart(),
                            next.getEnd(),
                            next.getFragment().getId().toString()
                    ));
                }
            }

        } catch (Exception e) {
            LOGGER.error("Unexpected error during slide check (Type: {}): {}", slideType, e.getMessage(), e);
        } finally {
            windowSliderProcessingFlag.set(false);
        }
    }

    private void doSlide(ZonedDateTime now,
                         SlideType slideType,
                         Duration timeDiff,
                         int currentKey,
                         int nextKey,
                         PlaylistFragmentRange current,
                         PlaylistFragmentRange next
    ) {
        if (!mainQueue.isEmpty() && keySet.current() == Collections.max(mainQueue.keySet())) {
            LOGGER.debug("Already showing newest fragment {}", keySet.current());
            return;
        }

        addToHistory(new SlideEvent(
                slideType,
                now,
                slideSequence.incrementAndGet(),
                timeDiff,
                currentKey,
                current != null ? current.getStart() : -1,
                current != null ? current.getEnd() : -1,
                current != null ? current.getFragment().getId().toString() : "N/A",
                now,
                nextKey,
                next != null ? next.getStart() : -1,
                next != null ? next.getEnd() : -1,
                next != null ? next.getFragment().getId().toString() : "N/A"
        ));

        if (current != null) {
            current.setStale(true);
        }

        keySet.slide();
    }

    private void addToHistory(SlideEvent event) {
        synchronized (slideHistory) {
            if (!slideHistory.isEmpty() && slideHistory.getLast().timestamp().equals(event.timestamp())) {
                slideHistory.removeLast();
            }
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

    private String getFormattedLastSlide() {
        return (lastSlide != null ? lastSlide : ZonedDateTime.now(ZONE_ID))
                .format(DateTimeFormatter.ISO_INSTANT);
    }

    public List<Long[]> getCurrentWindow() {
        try {
            return List.of(
                    extractRange(keySet.current()),
                    extractRange(keySet.next())
                    //  extractRange(keySet.future())
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