package io.kneo.broadcaster.service;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.controller.stream.HlsSegment;
import io.kneo.broadcaster.model.SoundMetadata;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Service for creating HLS segments from audio files
 * Uses reactive programming patterns for better resource management
 */
@ApplicationScoped
public class ReactiveAudioSegmentationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReactiveAudioSegmentationService.class);

    private final BroadcasterConfig config;
    private final Executor audioProcessingExecutor;

    @Inject
    public ReactiveAudioSegmentationService(BroadcasterConfig config) {
        this.config = config;
        // Use a dedicated thread pool for CPU-intensive audio processing
        this.audioProcessingExecutor = Executors.newFixedThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors() / 2)
        );
    }

    /**
     * Create an HLS segment from the given audio file for a specific timestamp
     *
     * @param filePath Path to the audio file
     * @param metadata Metadata for the audio
     * @param id Unique identifier for the audio
     * @param timestamp Unix timestamp for this segment (aligned to wall clock)
     * @param duration Duration in seconds for this segment
     * @return Uni with the created HLS segment
     */
    public Uni<HlsSegment> createSegment(
            String filePath,
            SoundMetadata metadata,
            String id,
            long timestamp,
            int duration) {

        return Uni.createFrom().emitter(emitter -> {
            CompletableFuture.supplyAsync(() -> {
                        try {
                            // In a real implementation, this would use ffmpeg or another tool to slice
                            // the audio file into a proper HLS segment (.ts file)

                            String songName = metadata != null ?
                                    String.format("%s - %s", metadata.getArtist(), metadata.getTitle()) :
                                    "Unknown Track";

                            Path path = Paths.get(filePath);
                            if (!Files.exists(path)) {
                                LOGGER.error("Audio file not found: {}", filePath);
                                throw new RuntimeException("Audio file not found: " + filePath);
                            }

                            // For demonstration: read the file bytes directly
                            // In production: would use ffmpeg to create a properly encoded TS segment
                            byte[] audioData = Files.readAllBytes(path);

                            // Create a new segment with the timestamp
                            HlsSegment segment = new HlsSegment();
                            segment.setSequenceNumber(timestamp);
                            segment.setTimestamp(timestamp); // Store timestamp explicitly
                            segment.setSongName(songName);
                            segment.setDuration(duration);
                            segment.setData(audioData);
                            segment.setSize(audioData.length);

                            LOGGER.debug("Created segment: timestamp={}, title={}, size={}bytes",
                                    timestamp, songName, audioData.length);

                            return segment;
                        } catch (Exception e) {
                            LOGGER.error("Error creating segment", e);
                            throw new RuntimeException("Failed to create segment", e);
                        }
                    }, audioProcessingExecutor)
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            emitter.fail(error);
                        } else {
                            emitter.complete(result);
                        }
                    });
        });
    }

    /**
     * Clean up resources when the service is shut down
     */
    public void shutdown() {
        if (audioProcessingExecutor instanceof java.util.concurrent.ExecutorService executorService) {
            executorService.shutdown();
        }
    }
}