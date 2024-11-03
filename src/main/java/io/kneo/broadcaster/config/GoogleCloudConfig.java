package io.kneo.broadcaster.config;

import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class GoogleCloudConfig {

    @Produces
    @ApplicationScoped
    public Storage storageClient() {
        return StorageOptions.getDefaultInstance().getService();
    }
}