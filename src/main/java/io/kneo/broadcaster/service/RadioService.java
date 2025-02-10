package io.kneo.broadcaster.service;


import io.kneo.broadcaster.config.HlsPlaylistConfig;
import io.kneo.broadcaster.config.RadioStationPool;
import io.kneo.broadcaster.controller.stream.Playlist;
import io.kneo.broadcaster.model.RadioStation;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.FileUpload;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

@ApplicationScoped
public class RadioService {
    private static final Logger LOGGER = LoggerFactory.getLogger(RadioService.class);

    @Inject
    HlsPlaylistConfig config;

    @Inject
    RadioStationPool radiostationPool;

    public Uni<Void> addFileUploadToPlaylist(String brand, FileUpload fileUpload) {
        RadioStation radio = radiostationPool.get(brand);
        return Uni.createFrom().item(() -> {
            try {
                byte[] fileData = Files.readAllBytes(Paths.get(fileUpload.uploadedFileName()));
                int segmentSize = config.getBufferSizeKb() * 1024;

                for (int i = 0; i < fileData.length; i += segmentSize) {
                    int segmentLength = Math.min(segmentSize, fileData.length - i);
                    byte[] segment = new byte[segmentLength];
                    System.arraycopy(fileData, i, segment, 0, segmentLength);
                    radio.getPlaylist().addSegment(segment);
                }
                LOGGER.info("Added uploaded file to playlist: {}, size: {}, with segment size: {}",
                        fileUpload.fileName(), fileUpload.size(), segmentSize);
                return null;
            } catch (IOException e) {
                LOGGER.error("Failed to process uploaded file: {}", fileUpload.fileName(), e);
                throw new RuntimeException("Failed to process uploaded file", e);
            }
        });
    }

    public Playlist getPlaylist(String brand) {
        RadioStation radio = radiostationPool.get(brand);
        return radio.getPlaylist();
    }
}