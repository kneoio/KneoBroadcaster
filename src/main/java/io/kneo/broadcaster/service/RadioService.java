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
        return radiostationPool.get(brand)
                .onItem().transformToUni(radio -> {
                    return Uni.createFrom().item(() -> {
                                try {
                                    return Files.readAllBytes(Paths.get(fileUpload.uploadedFileName()));
                                } catch (IOException e) {
                                    throw new RuntimeException("Failed to read uploaded file", e);
                                }
                            })
                            .onItem().transformToUni(fileData -> {
                                int segmentSize = config.getBufferSizeKb() * 1024;

                                // Process the file in chunks and add segments to the playlist
                                for (int i = 0; i < fileData.length; i += segmentSize) {
                                    int segmentLength = Math.min(segmentSize, fileData.length - i);
                                    byte[] segment = new byte[segmentLength];
                                    System.arraycopy(fileData, i, segment, 0, segmentLength);
                                    radio.getPlaylist().addSegment(segment);
                                }

                                LOGGER.info("Added uploaded file to playlist: {}, size: {}, with segment size: {}",
                                        fileUpload.fileName(), fileUpload.size(), segmentSize);
                                return Uni.createFrom().voidItem();
                            });
                })
                .onFailure().invoke(failure -> {
                    LOGGER.error("Failed to process uploaded file: {}", fileUpload.fileName(), failure);
                });
    }

    public Uni<Playlist> getPlaylist(String brand) {
        return radiostationPool.get(brand)
                .onItem().transform(RadioStation::getPlaylist)
                .onFailure().invoke(failure -> {
                    LOGGER.error("Failed to get playlist for brand: {}", brand, failure);
                });
    }
}