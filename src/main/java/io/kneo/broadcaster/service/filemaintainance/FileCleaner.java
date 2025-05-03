package io.kneo.broadcaster.service.filemaintainance;

import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@ApplicationScoped
public class FileCleaner {
    private static final Logger LOGGER = LoggerFactory.getLogger(FileCleaner.class);

    public void cleanOldFiles(Path directory, long ageThresholdHours) {
        try {
            LOGGER.info("Starting file cleanup in directory: {}", directory);

            if (!Files.exists(directory)) {
                LOGGER.debug("Directory does not exist: {}", directory);
                return;
            }

            Instant threshold = Instant.now().minus(ageThresholdHours, ChronoUnit.HOURS);

            Files.walk(directory)
                    .filter(Files::isRegularFile)
                    .filter(path -> isOlderThan(path, threshold))
                    .forEach(this::deleteFile);

            cleanEmptyDirectories(directory);

            LOGGER.info("File cleanup in directory {} completed", directory);
        } catch (IOException e) {
            LOGGER.error("Error during file cleanup in {}", directory, e);
        }
    }

    private void cleanEmptyDirectories(Path directory) throws IOException {
        Files.walk(directory)
                .filter(Files::isDirectory)
                .filter(path -> !path.equals(directory)) // Don't delete the root directory
                .filter(this::isEmptyDirectory)
                .forEach(this::deleteDirectory);
    }

    private boolean isEmptyDirectory(Path path) {
        try {
            return Files.list(path).count() == 0;
        } catch (IOException e) {
            LOGGER.warn("Could not check directory contents: {}", path, e);
            return false;
        }
    }

    private void deleteDirectory(Path dir) {
        try {
            Files.delete(dir);
            LOGGER.debug("Deleted empty directory: {}", dir);
        } catch (IOException e) {
            LOGGER.warn("Could not delete directory: {}", dir, e);
        }
    }

    private boolean isOlderThan(Path file, Instant threshold) {
        try {
            FileTime lastModifiedTime = Files.getLastModifiedTime(file);
            return lastModifiedTime.toInstant().isBefore(threshold);
        } catch (IOException e) {
            LOGGER.warn("Could not check modification time for file: {}", file, e);
            return false;
        }
    }

    private void deleteFile(Path file) {
        try {
            Files.delete(file);
            LOGGER.debug("Deleted old file: {}", file);
        } catch (IOException e) {
            LOGGER.warn("Could not delete file: {}", file, e);
        }
    }
}