package io.kneo.broadcaster.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

@ConfigMapping(prefix = "hetzner.storage")
public interface HetznerConfig {

    @WithName("access.key")
    String getAccessKey();

    @WithName("secret.key")
    String getSecretKey();

    @WithName("bucket.name")
    String getBucketName();

    @WithName("region")
    @WithDefault("eu-central")
    String getRegion();

    @WithName("endpoint")
    @WithDefault("hel1.your-objectstorage.com")
    String getEndpoint();

}