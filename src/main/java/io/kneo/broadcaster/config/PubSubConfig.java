package io.kneo.broadcaster.config;
import io.smallrye.config.ConfigMapping;

@ConfigMapping(prefix = "config")
public interface PubSubConfig {
    String projectId();
    String subscriptionId();
    String bucketId();
    String ffmpeg();

}

