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
    import java.time.Instant;
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
        private ZonedDateTime currentSegmentEndTime =  ZonedDateTime.now(LISBON_ZONE)
                .plusMinutes(3);
        @Getter
        private final Map<SlideType, AtomicInteger> slideCounters = new EnumMap<>(SlideType.class);

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
            slideCounters.put(SlideType.TIMER, new AtomicInteger(0));
            slideCounters.put(SlideType.EMERGENCY, new AtomicInteger(0));
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
                    timestamp -> {
                        LOGGER.debug("Slider tick: {}", timestamp);
                        slide(timestamp, SlideType.TIMER);
                    },
                    error -> LOGGER.error("Slider subscription error", error)
            );
            Cancellable janitor = janitorTimer.getTicker().subscribe().with(
                    timestamp -> {
                        LOGGER.debug("Janitor tick: {}", timestamp);
                        clean();
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
                    slideExecutor.execute(() -> slide(System.currentTimeMillis(), SlideType.EMERGENCY));
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
                if (mainQueue.size() < 5) {
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

        private void clean() {
            if (mainQueue.isEmpty()) {
                return;
            }

            Integer newestStaleKey = null;
            Set<Integer> staleKeysToRemove = new HashSet<>();

            for (Map.Entry<Integer, PlaylistFragmentRange> entry : mainQueue.entrySet()) {
                if (entry.getValue().isStale()) {
                    if (newestStaleKey == null || entry.getKey() > newestStaleKey) {
                        if (newestStaleKey != null) {
                            staleKeysToRemove.add(newestStaleKey);
                        }
                        newestStaleKey = entry.getKey();
                    } else {
                        staleKeysToRemove.add(entry.getKey());
                    }
                }
            }

            staleKeysToRemove.forEach(key -> {
                mainQueue.remove(key);
                LOGGER.debug("Removed stale fragment range with key: {}", key);
            });
        }

        private void slide(long timestampMillis, SlideType slideType) {
            if (!windowSliderProcessingFlag.compareAndSet(false, true)) {
                LOGGER.trace("Slide skipped - already in progress (Type: {})", slideType);
                return;
            }

            try {
                ZonedDateTime now = ZonedDateTime.now(LISBON_ZONE);
                ZonedDateTime timestamp = Instant.ofEpochMilli(timestampMillis).atZone(LISBON_ZONE);

                // Handle emergency slides differently
                if (slideType == SlideType.EMERGENCY) {
                    LOGGER.warn("EMERGENCY SLIDE TRIGGERED at {}", now);
                    performSlideOperations(now, slideType);
                    return;
                }

                // Timer slide logic
                if (currentSegmentEndTime == null) {
                    LOGGER.warn("Initializing missing slide schedule");
                    currentSegmentEndTime = now.plusSeconds(config.getSegmentDuration());
                }

                Duration remaining = Duration.between(now, currentSegmentEndTime);

                // Allow slide if we're within 5 seconds of scheduled time (early or late)
                if (remaining.abs().toMillis() <= 5000) {
                    performSlideOperations(now, slideType);
                }
                // Handle late slides (more than 5 seconds)
                else if (remaining.isNegative()) {
                    LOGGER.warn("LATE SLIDE ({}ms overdue) - Type: {}",
                            -remaining.toMillis(), slideType);
                    performSlideOperations(now, slideType);
                }
                // Handle early attempts
                else {
                    LOGGER.warn("Slide called {}ms early - Type: {}",
                            remaining.toMillis(), slideType);
                }
            } catch (Exception e) {
                LOGGER.error("{} slide failed", slideType, e);
            } finally {
                windowSliderProcessingFlag.set(false);
            }
        }

        private void performSlideOperations(ZonedDateTime timestamp, SlideType slideType) {
            // Mark current range as stale if exists
            if (mainQueue.containsKey(keySet.current())) {
                mainQueue.get(keySet.current()).setStale(true);
            }

            // Update playlist window
            keySet.slide();

            // Update tracking timestamps
            currentSegmentEndTime = timestamp.plusSeconds(config.getSegmentDuration());
            lastSlide = timestamp;
            slideCounters.get(slideType).incrementAndGet();

            LOGGER.info("{} slide completed at {}. Next at {}",
                    slideType,
                    timestamp.format(DateTimeFormatter.ISO_TIME),
                    currentSegmentEndTime.format(DateTimeFormatter.ISO_TIME));
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