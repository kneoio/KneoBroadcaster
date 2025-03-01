package io.kneo.broadcaster.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

@ConfigMapping(prefix = "radio.main")
public interface MainConfig {
    @WithName("basedir")
    @WithDefault("uploads")
    String getBaseDir();

}
