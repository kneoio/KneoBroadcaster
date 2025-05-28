package io.kneo.broadcaster.repository.file;

import io.smallrye.mutiny.Uni;

import java.util.UUID;

public interface IFileStorageStrategy {

    Uni<String> storeFile(String key, String filePath, String mimeType, String tableName, UUID id);
    Uni<String> storeFile(String key, byte[] fileContent, String mimeType, String tableName, UUID id);

    Uni<byte[]> retrieveFile(String key);

    Uni<Void> deleteFile(String key);

    String getStorageType();

}
