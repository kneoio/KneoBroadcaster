package io.kneo.broadcaster.service;

import io.kneo.broadcaster.model.FragmentStatus;
import io.kneo.broadcaster.model.SoundFragment;
import io.kneo.broadcaster.model.SourceType;
import io.kneo.broadcaster.store.AudioFileStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@ApplicationScoped
public class AudioService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AudioService.class);

    @Inject
    AudioFileStore audioFileStore;

    public SoundFragment processUploadedFile(Path filePath, String fileName) {
        try {
            LOGGER.info("Processing uploaded file: {}", fileName);

            SoundFragment soundFragment = SoundFragment.builder()
                    .source(SourceType.LOCAL_DISC)
                    .status(FragmentStatus.NOT_PROCESSED)
                    .name(fileName)
                    .type("song")
                    .createdAt(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                    .build();

            byte[] fileContent = Files.readAllBytes(filePath);
            soundFragment.setFile(fileContent);

            SoundFragment savedFragment = audioFileStore.saveFragment(soundFragment);
            LOGGER.info("Saved fragment with ID: {}", savedFragment.getId());
            return savedFragment;
        } catch (Exception e) {
            LOGGER.error("Error processing file", e);
            throw new RuntimeException("Error processing file", e);
        }
    }

    public void executeAction(int id, String actionType) {
        try {
            LOGGER.info("Executing action {} on fragment {}", actionType, id);

            SoundFragment fragment = audioFileStore.getFragment(id);
            if (fragment == null) {
                throw new RuntimeException("Fragment not found: " + id);
            }

            switch (actionType.toLowerCase()) {
                case "play":
                   // playFragment(fragment);
                    break;
                case "stop":
                //    stopFragment(fragment);
                    break;
                case "pause":
                //    pauseFragment(fragment);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown action type: " + actionType);
            }

        } catch (Exception e) {
            LOGGER.error("Error executing action {} on fragment {}", actionType, id, e);
            throw new RuntimeException("Error executing action", e);
        }
    }


}