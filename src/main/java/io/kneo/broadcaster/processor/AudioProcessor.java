package io.kneo.broadcaster.processor;

import io.kneo.broadcaster.model.FragmentStatus;
import io.kneo.broadcaster.model.SoundFragment;
import io.kneo.broadcaster.service.ActionResultType;
import io.kneo.broadcaster.store.AudioFileStore;
import io.kneo.broadcaster.stream.HlsPlaylist;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;


@ApplicationScoped
public class AudioProcessor {
    private static final Logger LOGGER = LoggerFactory.getLogger(AudioProcessor.class);
    private static final int SEGMENT_DURATION = 10;
    private static final int BUFFER_SIZE = 8192;
    private static final long FFMPEG_TIMEOUT = 30; // seconds

    @Inject
    private AudioFileStore audioFileStore;

    @Inject
    private HlsPlaylist hlsPlaylist;

    public CompletableFuture<ActionResultType> processUnprocessedFragments() {
        List<SoundFragment> unprocessedFragments = audioFileStore.getFragmentsByStatus(FragmentStatus.NOT_PROCESSED);

        return CompletableFuture.supplyAsync(() -> {
            for (SoundFragment fragment : unprocessedFragments) {
                try {
                    LOGGER.info("Processing fragment ID: {}", fragment.getId());
                    processFragment(fragment);
                } catch (Exception e) {
                    LOGGER.error("Failed to process fragment ID: {}", fragment.getId(), e);
                    return ActionResultType.FAIL;
                }
            }
            return ActionResultType.SUCCESS;
        });
    }

    private void processFragment(SoundFragment fragment) throws IOException, InterruptedException {
        List<byte[]> segments = convertToSegments(fragment.getFile());
        int count = 0;
        if (!segments.isEmpty()) {
            for (byte[] segmentData : segments) {
                synchronized (hlsPlaylist) {
                    hlsPlaylist.addSegment(segmentData);
                }
                count++;
            }

            audioFileStore.updateStatus(fragment.getId(), FragmentStatus.CONVERTED);

            LOGGER.info("Successfully processed and added to playlist - fragment ID: {} as {} segments",
                    fragment.getId(), count);
        } else {
            LOGGER.warn("No segments were created for fragment ID: {}", fragment.getId());
        }
    }

    private List<byte[]> convertToSegments(byte[] audioData) throws IOException, InterruptedException {
        List<byte[]> segments = new ArrayList<>();
        File tempDir = new File(System.getProperty("java.io.tmpdir"), "audio_segments");
        tempDir.mkdirs();

        // Create temporary input file
        File inputFile = File.createTempFile("input", ".mp3", tempDir);
        try (FileOutputStream fos = new FileOutputStream(inputFile)) {
            fos.write(audioData);
        }

        // Create temporary output pattern
        String outputPattern = new File(tempDir, "segment%03d.ts").getAbsolutePath();

        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg",
                "-i", inputFile.getAbsolutePath(),
                "-c:a", "aac",
                "-b:a", "128k",
                "-ar", "44100",
                "-ac", "2",
                "-f", "segment",
                "-segment_time", String.valueOf(SEGMENT_DURATION),
                "-segment_format", "mpegts",
                "-segment_list", new File(tempDir, "playlist.m3u8").getAbsolutePath(),
                outputPattern
        );

        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        Process process = pb.start();

        // Wait for FFmpeg to finish with timeout
        if (!process.waitFor(FFMPEG_TIMEOUT, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("FFmpeg process timed out");
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new IOException("FFmpeg failed with exit code: " + exitCode);
        }

        // Read generated segments
        File[] segmentFiles = tempDir.listFiles((dir, name) -> name.matches("segment\\d+\\.ts"));
        if (segmentFiles != null) {
            for (File segmentFile : segmentFiles) {
                try (FileInputStream fis = new FileInputStream(segmentFile)) {
                    byte[] segmentData = fis.readAllBytes();
                    synchronized (hlsPlaylist) {
                        hlsPlaylist.addSegment(segmentData);
                    }
                    segments.add(segmentData);
                }
                segmentFile.delete();
            }
        }

        // Cleanup
        inputFile.delete();
        new File(tempDir, "playlist.m3u8").delete();
        tempDir.delete();

        return segments;
    }
}
