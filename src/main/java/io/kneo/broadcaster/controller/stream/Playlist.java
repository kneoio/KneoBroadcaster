package io.kneo.broadcaster.controller.stream;

import io.kneo.broadcaster.config.HlsPlaylistConfig;
import io.kneo.broadcaster.model.BrandSoundFragment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class Playlist {
    private static final Logger LOGGER = LoggerFactory.getLogger(Playlist.class);
    private final ConcurrentNavigableMap<Integer, HlsSegment> segments = new ConcurrentSkipListMap<>();
    private final AtomicInteger currentSequence = new AtomicInteger(0);
    private final AtomicLong totalBytesProcessed = new AtomicLong(0);
    private final AtomicInteger segmentsCreated = new AtomicInteger(0);
    private final HlsPlaylistConfig config;

    public Playlist(HlsPlaylistConfig config) {
        this.config = config;
    }

    public String generatePlaylist() {
        StringBuilder playlist = new StringBuilder(segments.size() * 100);
        playlist.append("#EXTM3U\n")
                .append("#EXT-X-VERSION:3\n")
                .append("#EXT-X-TARGETDURATION:").append(config.getSegmentDuration()).append("\n")
                .append("#EXT-X-MEDIA-SEQUENCE:").append(segments.firstKey()).append("\n");

        segments.values().forEach(segment -> {
            String songName = segment.getSongName();
            playlist.append("#EXTINF:")
                    .append(segment.getDuration())
                    .append(",")
                    .append(songName)
                    .append("\n")
                    .append("segments/")
                    .append(segment.getSequenceNumber())
                    .append(".ts\n");
        });

        return playlist.toString();
    }

    public HlsSegment getSegment(int sequence) {
        return segments.get(sequence);
    }

    public int getSegmentCount() {
        return segments.size();
    }

    public void addSegment(BrandSoundFragment brandSoundFragment) throws IOException {
        if (brandSoundFragment == null ) {
            LOGGER.warn("Attempted to add empty segment");
            return;
        }
        Path filePath = brandSoundFragment.getSoundFragment().getFilePath();
        File fragmentFile = filePath.toFile();

        int sequence = currentSequence.getAndIncrement();
        HlsSegment segment = new HlsSegment(
                sequence,
                Files.readAllBytes(fragmentFile.toPath()),
                config.getSegmentDuration(),
                brandSoundFragment.getId(),
                String.format(brandSoundFragment.getSoundFragment().getArtist() + " - " + brandSoundFragment.getSoundFragment().getTitle())
        );
        segments.put(sequence, segment);

        totalBytesProcessed.addAndGet(fragmentFile.length());
        segmentsCreated.incrementAndGet();

        cleanupIfNeeded(sequence);
    }

    private void cleanupIfNeeded(int currentSeq) {
        if (segments.size() > config.getMaxSegments()) {
            int oldestAllowed = Math.max(currentSeq - config.getMaxSegments(), 0);
            segments.headMap(oldestAllowed).clear();
        }
    }
}