package io.kneo.broadcaster.repository.file;

import io.kneo.broadcaster.model.cnst.FileStorageType;
import io.kneo.broadcaster.model.FileMetadata;
import io.smallrye.mutiny.Uni;

import java.util.UUID;

public interface IFileStorage {

    Uni<String> storeFile(String key, String filePath, String mimeType, String tableName, UUID id);

    Uni<String> storeFile(String key, byte[] fileContent, String mimeType, String tableName, UUID id);

    Uni<FileMetadata> retrieveFile(String key);

    Uni<Void> deleteFile(String key);

    FileStorageType getStorageType();
}