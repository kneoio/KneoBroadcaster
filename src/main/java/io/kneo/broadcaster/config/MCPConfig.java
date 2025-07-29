package io.kneo.broadcaster.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

@ConfigMapping(prefix = "mcp")
public interface MCPConfig {
    @WithName("server.enabled")
    @WithDefault("true")
    boolean isServerEnabled();

    @WithName("server.port")
    @WithDefault("38708")
    int getServerPort();

    @WithName("server.host")
    @WithDefault("localhost")
    String getServerHost();

    @WithName("protocol.version")
    @WithDefault("2024-11-05")
    String getProtocolVersion();

    @WithName("server.name")
    @WithDefault("KneoBroadcaster-MCP")
    String getServerName();

    @WithName("server.version")
    @WithDefault("1.0.0")
    String getServerVersion();
}