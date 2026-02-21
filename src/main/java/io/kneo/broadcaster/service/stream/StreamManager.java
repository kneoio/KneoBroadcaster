package io.kneo.broadcaster.service.stream;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.config.HlsPlaylistConfig;
import io.kneo.broadcaster.model.cnst.ManagedBy;
import io.kneo.broadcaster.model.cnst.StreamStatus;
import io.kneo.broadcaster.model.live.LiveSoundFragment;
import io.kneo.broadcaster.model.stream.IStream;
import io.kneo.broadcaster.service.live.AiHelperService;
import io.kneo.broadcaster.service.manipulation.segmentation.AudioSegmentationService;
import io.kneo.broadcaster.service.playlist.ISupplier;
import io.kneo.broadcaster.service.playlist.PlaylistManager;
import io.kneo.broadcaster.service.playlist.SongSupplier;
import io.kneo.broadcaster.service.soundfragment.BrandSoundFragmentUpdateService;
import io.kneo.broadcaster.service.soundfragment.SoundFragmentService;
import io.smallrye.mutiny.subscription.Cancellable;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StreamManager implements IStreamManager {
    private static final ZoneId ZONE_ID = ZoneId.of("Europe/Lisbon");
    private static final Logger LOGGER = LoggerFactory.getLogger(StreamManager.class);
    private static final Pattern SEGMENT_PATTERN = Pattern.compile("([^_]+)_([0-9]+)_([0-9]+)\\.ts$");

    private final ConcurrentSkipListMap<Long, Map<Long, HlsSegment>> liveSegments = new ConcurrentSkipListMap<>();
    private final AtomicLong currentSequence = new AtomicLong(0);
    private final Queue<Map<Long, HlsSegment>> pendingFragmentSegmentsQueue = new LinkedList<>();
    private static final int SEGMENTS_TO_DRIP_PER_FEED_CALL = 1;

    @Getter
    private IStream stream;
    @Getter
    private PlaylistManager playlistManager;
    private final BroadcasterConfig broadcasterConfig;
    @Getter
    private final HlsPlaylistConfig config;
    @Getter
    private final SoundFragmentService soundFragmentService;
    @Getter
    private final AudioSegmentationService segmentationService;
    private final ISupplier songSupplier;
    private final SegmentFeederTimer segmentFeederTimer;
    private final SliderTimer sliderTimer;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final BrandSoundFragmentUpdateService updateService;

    private final int maxVisibleSegments = 20;
    private static final int PENDING_QUEUE_REFILL_THRESHOLD = 10;

    private final Map<String, Cancellable> timerSubscriptions = new ConcurrentHashMap<>();
    private final Map<String, Long> clientBitrate = new ConcurrentHashMap<>();
    private final Object fragmentRetrievalLock = new Object();
    private final AiHelperService aiHelperService;

    public StreamManager(
            HlsPlaylistConfig config,
            BroadcasterConfig broadcasterConfig,
            SliderTimer sliderTimer,
            SegmentFeederTimer segmentFeederTimer,
            SoundFragmentService soundFragmentService,
            AudioSegmentationService segmentationService,
            SongSupplier songSupplier,
            BrandSoundFragmentUpdateService updateService,
            AiHelperService aiHelperService
    ) {
        this.config = config;
        this.sliderTimer = sliderTimer;
        segmentFeederTimer.setDurationSec(config.getSegmentDuration());
        this.segmentFeederTimer = segmentFeederTimer;
        this.broadcasterConfig = broadcasterConfig;
        this.soundFragmentService = soundFragmentService;
        this.segmentationService = segmentationService;
        this.songSupplier = songSupplier;
        this.updateService = updateService;
        this.aiHelperService = aiHelperService;
    }

    @Override
    public void initialize(IStream stream) {
        stream.setStatus(StreamStatus.WARMING_UP);
        this.stream = stream;
        LOGGER.info("New broadcast initialized for {}", stream.getSlugName());

        playlistManager = new PlaylistManager(
                config,
                broadcasterConfig,
                this,
                songSupplier,
                updateService,
                aiHelperService,
                stream.getStreamLanguage()
        );
        if (stream.getManagedBy() == ManagedBy.ITSELF) {
            playlistManager.startSelfManaging();
        }

        Cancellable feeder = segmentFeederTimer.getTicker().subscribe().with(
                timestamp -> executorService.submit(this::feedSegments),
                error -> LOGGER.error("Feeder subscription error for {}: {}", stream.getSlugName(), error.getMessage(), error)
        );

        Cancellable slider = sliderTimer.getTicker().subscribe().with(
                timestamp -> executorService.submit(this::slideWindow),
                error -> LOGGER.error("Slider subscription error for {}: {}", stream.getSlugName(), error.getMessage(), error)
        );

        timerSubscriptions.put("feeder", feeder);
        timerSubscriptions.put("slider", slider);
    }

    public void feedSegments() {
        if (!pendingFragmentSegmentsQueue.isEmpty()) {
            for (int i = 0; i < SEGMENTS_TO_DRIP_PER_FEED_CALL; i++) {
                if (liveSegments.size() >= maxVisibleSegments * 2) {
                    System.out.printf("feedSegments Debug: [DRIP] liveSegments buffer for %s is full or at limit (%d/%d). Pausing drip-feed for this call.%n",
                            stream.getSlugName(), liveSegments.size(), maxVisibleSegments * 2);
                    break;
                }
                Map<Long, HlsSegment> bitrateSlot = pendingFragmentSegmentsQueue.poll();
                long seq = bitrateSlot.values().iterator().next().getSequence();
                liveSegments.put(seq, bitrateSlot);
            }
        }

        if (pendingFragmentSegmentsQueue.size() < PENDING_QUEUE_REFILL_THRESHOLD) {
            try {
                synchronized (fragmentRetrievalLock) {
                    LiveSoundFragment fragment = playlistManager.getNextFragment();
                    if (fragment != null && !fragment.getSegments().isEmpty()) {
                        Map<Long, ConcurrentLinkedQueue<HlsSegment>> segmentsByBitrate = fragment.getSegments();
                        long maxBitrate = stream.getBitRate();
                        ConcurrentLinkedQueue<HlsSegment> maxBitrateQueue = segmentsByBitrate.get(maxBitrate);
                        if (maxBitrateQueue == null || maxBitrateQueue.isEmpty()) {
                            return;
                        }
                        int segmentCount = maxBitrateQueue.size();
                        Map<Long, HlsSegment[]> bitrateArrays = new HashMap<>();
                        for (Map.Entry<Long, ConcurrentLinkedQueue<HlsSegment>> entry : segmentsByBitrate.entrySet()) {
                            bitrateArrays.put(entry.getKey(), entry.getValue().toArray(new HlsSegment[0]));
                        }
                        boolean isFirst = true;
                        for (int i = 0; i < segmentCount; i++) {
                            long seq = currentSequence.getAndIncrement();
                            Map<Long, HlsSegment> bitrateSlot = new HashMap<>();
                            for (Map.Entry<Long, HlsSegment[]> entry : bitrateArrays.entrySet()) {
                                HlsSegment[] arr = entry.getValue();
                                if (i < arr.length) {
                                    HlsSegment seg = arr[i];
                                    seg.setSequence(seq);
                                    seg.setLiveSoundFragment(fragment);
                                    seg.setFirstSegmentOfFragment(isFirst);
                                    bitrateSlot.put(entry.getKey(), seg);
                                }
                            }
                            if (!bitrateSlot.isEmpty()) {
                                pendingFragmentSegmentsQueue.offer(bitrateSlot);
                            }
                            isFirst = false;
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Error in feedSegments for {}: {}", stream.getSlugName(), e.getMessage(), e);
            }
        }
    }

    private void slideWindow() {
        if (liveSegments.isEmpty()) {
            return;
        }
        while (liveSegments.size() > maxVisibleSegments) {
            liveSegments.pollFirstEntry();
        }
    }

    @Override
    public String generateMasterPlaylist() {
        StringBuilder master = new StringBuilder();
        master.append("#EXTM3U\n");
        String slug = stream.getSlugName();
        long maxRate = stream.getBitRate();
        long halfRate = maxRate / 2;
        master.append("#EXT-X-STREAM-INF:BANDWIDTH=").append(maxRate * 1000).append("\n");
        master.append("/").append(slug).append("/radio/stream.m3u8?bitrate=").append(maxRate).append("\n");
        master.append("#EXT-X-STREAM-INF:BANDWIDTH=").append(halfRate * 1000).append("\n");
        master.append("/").append(slug).append("/radio/stream.m3u8?bitrate=").append(halfRate).append("\n");
        return master.toString();
    }

    @Override
    public String generatePlaylist(String clientId) {
        long requestedBitrate = stream.getBitRate();
        if (clientId != null) {
            try {
                requestedBitrate = Long.parseLong(clientId);
            } catch (NumberFormatException ignored) {
            }
        }
        clientBitrate.put(clientId != null ? clientId : "default", requestedBitrate);

        if (liveSegments.isEmpty()) {
            return "#EXTM3U\n" +
                    "#EXT-X-VERSION:3\n" +
                    "#EXT-X-ALLOW-CACHE:NO\n" +
                    "#EXT-X-TARGETDURATION:" + config.getSegmentDuration() + "\n" +
                    "#EXT-X-MEDIA-SEQUENCE:0\n";
        }

        final long bitrate = requestedBitrate;
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

        String slug = (this.stream != null && this.stream.getSlugName() != null)
                ? this.stream.getSlugName() : "default_station_path";

        liveSegments.tailMap(firstSequenceInWindow).entrySet().stream()
                .limit(maxVisibleSegments)
                .forEach(entry -> {
                    Map<Long, HlsSegment> bitrateSlot = entry.getValue();
                    HlsSegment segment = bitrateSlot.containsKey(bitrate)
                            ? bitrateSlot.get(bitrate)
                            : bitrateSlot.values().iterator().next();
                    playlist.append("#EXTINF:")
                            .append(segment.getDuration())
                            .append(",")
                            .append(segment.getSongMetadata().toString())
                            .append("\n")
                            .append("segments/")
                            .append(slug)
                            .append("_")
                            .append(bitrate)
                            .append("_")
                            .append(segment.getSequence())
                            .append(".ts\n");
                });

        return playlist.toString();
    }

    @Override
    public HlsSegment getSegment(long sequence) {
        Map<Long, HlsSegment> bitrateSlot = liveSegments.get(sequence);
        if (bitrateSlot == null) return null;
        return bitrateSlot.get(stream.getBitRate());
    }

    @Override
    public HlsSegment getSegment(String segmentParam) {
        try {
            Matcher matcher = SEGMENT_PATTERN.matcher(segmentParam);
            if (!matcher.find()) {
                LOGGER.warn("Segment '{}' doesn't match expected pattern: {}", segmentParam, SEGMENT_PATTERN.pattern());
                return null;
            }
            long bitrate = Long.parseLong(matcher.group(2));
            long sequence = Long.parseLong(matcher.group(3));
            Map<Long, HlsSegment> bitrateSlot = liveSegments.get(sequence);
            if (bitrateSlot == null) {
                LOGGER.debug("Segment sequence {} not found in liveSegments", sequence);
                return null;
            }
            HlsSegment segment = bitrateSlot.get(bitrate);
            if (segment == null) {
                LOGGER.debug("Bitrate {} not found for sequence {}", bitrate, sequence);
            }
            return segment;
        } catch (Exception e) {
            LOGGER.warn("Error processing segment request '{}' : {}", segmentParam, e.getMessage(), e);
            return null;
        }
    }

    @Override
    public StreamManagerStats getStats() {
        Map<Long, HlsSegment> flatView = new LinkedHashMap<>();
        liveSegments.forEach((seq, bitrateSlot) -> {
            HlsSegment seg = bitrateSlot.get(stream.getBitRate());
            if (seg == null && !bitrateSlot.isEmpty()) seg = bitrateSlot.values().iterator().next();
            if (seg != null) flatView.put(seq, seg);
        });
        return new StreamManagerStats(flatView, getSegmentHeartbeat());
    }

    @Override
    public void shutdown() {
        LOGGER.info("Shutting down StreamManager for: {}", stream.getSlugName());

        if (playlistManager != null) {
            playlistManager.shutdown();
        }

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
        clientBitrate.clear();
        LOGGER.info("StreamManager for {} has been shut down. All queues cleared.", stream.getSlugName());
        if (stream != null) {
            stream.setStatus(StreamStatus.OFF_LINE);
        }
    }

    public boolean getSegmentHeartbeat() {
        return !liveSegments.isEmpty();
    }
}