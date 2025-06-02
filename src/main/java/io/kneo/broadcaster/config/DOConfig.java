package io.kneo.broadcaster.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

@ConfigMapping(prefix = "do.spaces")
public interface DOConfig {

    @WithName("access.key")
    String getAccessKey();

    @WithName("secret.key")
    String getSecretKey();

    @WithName("bucket.name")
    String getBucketName();

    @WithName("region")
    @WithDefault("fra1")
    String getRegion();

    //https://soundfragments.fra1.digitaloceanspaces.com

    @WithName("endpoint")
    @WithDefault("digitaloceanspaces.com")
    String getEndpoint();

    @WithName("orphan.cleaning.disabled")
    @WithDefault("true")
    boolean isDeleteDisabled();


}