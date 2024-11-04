package io.kneo.broadcaster.processor;

import io.kneo.broadcaster.model.FragmentStatus;
import io.kneo.broadcaster.model.SoundFragment;
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

@ApplicationScoped
public class AudioProcessor {
    private static final Logger LOGGER = LoggerFactory.getLogger(AudioProcessor.class);
    private static final int SEGMENT_DURATION = 10;
    private static final int BUFFER_SIZE = 8192;

    @Inject
    private AudioFileStore audioFileStore;

    @Inject
    private HlsPlaylist hlsPlaylist;

    public void processUnprocessedFragments() {
        List<SoundFragment> unprocessedFragments = audioFileStore.getFragmentsByStatus(FragmentStatus.NOT_PROCESSED);

        for (SoundFragment fragment : unprocessedFragments) {
            try {
                LOGGER.info("Processing fragment ID: {}", fragment.getId());
                                                  processFragment(fragment);
            } catch (IOException | InterruptedException e) {
                LOGGER.error("Failed to process fragment ID: {}", fragment.getId(), e);
            }
        }
    }

    private void processFragment(SoundFragment fragment) throws IOException, InterruptedException {
        List<byte[]> segments = convertToSegments(fragment.getFile());

        if (!segments.isEmpty()) {
            for (byte[] segmentData : segments) {
                hlsPlaylist.addSegment(segmentData);
            }

            fragment.setStatus(FragmentStatus.CONVERTED);
            audioFileStore.update(fragment);

            LOGGER.info("Successfully processed and added to playlist - fragment ID: {} as {} segments",
                    fragment.getId(), segments.size());
        } else {
            LOGGER.warn("No segments were created for fragment ID: {}", fragment.getId());
        }
    }

    private List<byte[]> convertToSegments(byte[] audioData) throws IOException, InterruptedException {
        List<byte[]> segments = new ArrayList<>();

        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg",
                "-i", "pipe:0",  // Read from stdin
                "-af", "apad",
                "-c:a", "aac",
                "-b:a", "192k",
                "-ar", "44100",
                "-ac", "2",
                "-segment_time", String.valueOf(SEGMENT_DURATION),
                "-f", "segment",
                "-reset_timestamps", "1",
                "pipe:1"  // Write to stdout
        );

        Process process = pb.start();

        // Write audio data to FFmpeg's stdin
        CompletableFuture.runAsync(() -> {
            try (OutputStream ffmpegInput = process.getOutputStream()) {
                ffmpegInput.write(audioData);
            } catch (IOException e) {
                LOGGER.error("Failed to write to FFmpeg input", e);
            }
        });

        // Read segments from FFmpeg's stdout
        try (InputStream ffmpegOutput = process.getInputStream();
             BufferedInputStream bis = new BufferedInputStream(ffmpegOutput)) {

            ByteArrayOutputStream segmentBuffer = new ByteArrayOutputStream();
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            int segmentMarkerCount = 0;

            while ((bytesRead = bis.read(buffer)) != -1) {
                for (int i = 0; i < bytesRead - 3; i++) {
                    // Look for MPEG-TS sync byte (0x47) and segment markers
                    if (buffer[i] == 0x47) {
                        segmentMarkerCount++;

                        // Assuming each segment has multiple TS packets
                        if (segmentMarkerCount >= 1000) {  // Adjust based on your segment size
                            if (segmentBuffer.size() > 0) {
                                segments.add(segmentBuffer.toByteArray());
                                segmentBuffer.reset();
                            }
                            segmentMarkerCount = 0;
                        }
                    }
                }
                segmentBuffer.write(buffer, 0, bytesRead);
            }

            // Add final segment if any
            if (segmentBuffer.size() > 0) {
                segments.add(segmentBuffer.toByteArray());
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("FFmpeg failed with exit code: " + exitCode);
        }

        return segments;
    }
}