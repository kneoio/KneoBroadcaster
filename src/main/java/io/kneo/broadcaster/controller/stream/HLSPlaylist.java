package io.kneo.broadcaster.controller.stream;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.config.HlsPlaylistConfig;
import io.kneo.broadcaster.model.stats.PlaylistStats;
import io.kneo.broadcaster.service.AudioSegmentationService;
import io.kneo.broadcaster.service.SoundFragmentService;
import io.kneo.broadcaster.service.radio.PlaylistKeeper;
import io.kneo.broadcaster.service.radio.PlaylistMaintenanceService;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class HLSPlaylist {
    private static final Logger LOGGER = LoggerFactory.getLogger(HLSPlaylist.class);
    private final ConcurrentNavigableMap<Long, HlsSegment> segments = new ConcurrentSkipListMap<>();
    private final AtomicLong currentSequence = new AtomicLong(0);
    private final AtomicLong lastRequestedSegment = new AtomicLong(0);
    private final AtomicLong totalBytesProcessed = new AtomicLong(0);
    private final AtomicReference<String> lastRequestedFragmentName = new AtomicReference<>("");
    private final AtomicLong segmentTimeStamp = new AtomicLong(0);

    private final ConcurrentLinkedQueue<PlaylistRange> mainQueue = new ConcurrentLinkedQueue<>();

    @Getter
    @Setter
    private String brandName;

    @Getter
    private final AudioSegmentationService segmentationService;

    @Getter
    private final SoundFragmentService soundFragmentService;

    @Getter
    private final PlaylistKeeper playlistKeeper;

    @Getter
    private final HlsPlaylistConfig config;

    @Getter
    private final PlaylistMaintenanceService maintenanceService;

    public HLSPlaylist(
            HlsPlaylistConfig config,
            BroadcasterConfig broadcasterConfig,
            SoundFragmentService soundFragmentService,
            String brandName) {
        this(config, broadcasterConfig, soundFragmentService, brandName, null, null);
    }

    public HLSPlaylist(
            HlsPlaylistConfig config,
            BroadcasterConfig broadcasterConfig,
            SoundFragmentService soundFragmentService,
            String brandName,
            PlaylistKeeper playlistScheduler,
            PlaylistMaintenanceService maintenanceService) {
        this.config = config;
        this.brandName = brandName;
        this.soundFragmentService = soundFragmentService;
        this.segmentationService = new AudioSegmentationService(broadcasterConfig);
        this.playlistKeeper = playlistScheduler;
        this.maintenanceService = maintenanceService;
        LOGGER.info("Created HLSPlaylist for brand: {}", brandName);
    }

    public void initialize() {
        LOGGER.info("New broadcast initialized for {}, sequence: {}", brandName, currentSequence.get());
        playlistKeeper.registerPlaylist(this);
        maintenanceService.initializePlaylistMaintenance(this);
    }

    public String generatePlaylist() {
        if (segments.isEmpty()) {
            LOGGER.warn("Attempted to generate playlist with no segments");
            return "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-ALLOW-CACHE:NO\n#EXT-X-TARGETDURATION:" +
                    config.getSegmentDuration() + "\n#EXT-X-MEDIA-SEQUENCE:" + currentSequence.get() +
                    "\n#EXT-X-DISCONTINUITY-SEQUENCE:" + (currentSequence.get() / 1000) + "\n";
        }

        PlaylistRange range = mainQueue.poll();
        if (range != null) {
            StringBuilder playlist = new StringBuilder(1000);
            playlist.append("#EXTM3U\n")
                    .append("#EXT-X-VERSION:3\n")
                    .append("#EXT-X-ALLOW-CACHE:NO\n")
                    .append("#EXT-X-START:TIME-OFFSET=0,PRECISE=YES\n")
                    .append("#EXT-X-TARGETDURATION:").append(config.getSegmentDuration()).append("\n")
                    .append("#EXT-X-MEDIA-SEQUENCE:").append(range.start()).append("\n");

            Map<Long, HlsSegment> rangeSegments = segments.subMap(range.start(), true, range.end(), true);

            rangeSegments.values()
                    .forEach(segment -> {
                        String songName = segment.getSongName();
                        // Always use timestamp for program date time
                        long timestamp = segment.getTimestamp() > 0 ? segment.getTimestamp() : System.currentTimeMillis() / 1000;
                        Instant segmentTime = Instant.ofEpochSecond(timestamp);
                        playlist.append("#EXT-X-PROGRAM-DATE-TIME:")
                                .append(DateTimeFormatter.ISO_INSTANT.format(segmentTime))
                                .append("\n");

                        playlist.append("#EXTINF:")
                                .append(segment.getDuration())
                                .append(",")
                                .append(songName)
                                .append("\n")
                                .append("segments/")
                                .append(brandName)
                                .append("_")
                                .append(timestamp)
                                .append(".ts\n");
                    });
            return playlist.toString();
        } else {
            return "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-ALLOW-CACHE:NO\n#EXT-X-TARGETDURATION:" +
                    config.getSegmentDuration() + "\n#EXT-X-MEDIA-SEQUENCE:" + currentSequence.get() +
                    "\n#EXT-X-DISCONTINUITY-SEQUENCE:" + (currentSequence.get() / 1000) + "\n";
        }
    }

    public HlsSegment getSegment(long sequence) {
        HlsSegment segment = segments.get(sequence);
        lastRequestedSegment.set(segment.getSequenceNumber());
        lastRequestedFragmentName.set(segment.getSongName());
        segmentTimeStamp.set(segment.getTimestamp());
        return segment;
    }

    public void addSegment(HlsSegment segment) {
        if (segment == null) {
            LOGGER.warn("Attempted to add null segment");
            return;
        }
        if (segment.getTimestamp() <= 0) {
            segment = new HlsSegment(
                    segment.getSequenceNumber(),
                    segment.getData(),
                    segment.getDuration(),
                    segment.getSoundFragmentId(),
                    segment.getSongName(),
                    System.currentTimeMillis() / 1000
            );
        }

        segments.put(segment.getSequenceNumber(), segment);
        totalBytesProcessed.addAndGet(segment.getSize());
    }

    public void addRangeToQueue(PlaylistRange range) {
        mainQueue.offer(range);
    }

    public int getSegmentCount() {
        return segments.size();
    }

    public long getCurrentSequenceAndIncrement() {
        return currentSequence.getAndIncrement();
    }

    public long getCurrentSequence() {
        return currentSequence.get();
    }

    public long getLastSegmentKey() {
        ConcurrentNavigableMap<Long, HlsSegment> map = segments;
        return map.isEmpty() ? 0L : map.lastKey();
    }

    public long getLastRequestedSegment() {
        return lastRequestedSegment.get();
    }

    public String getLastRequestedFragmentName() {
        return lastRequestedFragmentName.get();
    }

    public long getSegmentTimeStamp() {
        return segmentTimeStamp.get();
    }

    public PlaylistStats getStats() {
        assert playlistKeeper != null;
        return PlaylistStats.fromPlaylist(this,
                playlistKeeper.getRecentlyPlayedTitles(brandName, 10));
    }

    public int getQueueSize() {
        return mainQueue.size();
    }

    public long getTotalBytesProcessed() {
        return totalBytesProcessed.get();
    }

    public Set<Long> getSegmentKeys() {
        return new HashSet<>(segments.keySet());
    }

    public void removeSegmentsBefore(Long oldestToKeep) {
        segments.headMap(oldestToKeep).clear();
    }

    public void shutdown() {
        LOGGER.info("Shutting down playlist for: {}", brandName);
        if (maintenanceService != null) {
            maintenanceService.shutdownPlaylistMaintenance(brandName);
        }
        segments.clear();
        currentSequence.set(0);
        totalBytesProcessed.set(0);
    }
}