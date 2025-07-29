package io.kneo.broadcaster.mcp;

import io.kneo.broadcaster.config.MCPConfig;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class MCPServerStartup {
    private static final Logger LOGGER = LoggerFactory.getLogger(MCPServerStartup.class);

    @Inject
    Vertx vertx;

    @Inject
    MCPConfig mcpConfig;

    @Inject
    SoundFragmentMCPTools soundFragmentMCPTools;

    @Inject
    MemoryMCPTools memoryMCPTools;

    void onStart(@Observes StartupEvent ev) {
        if (!mcpConfig.isServerEnabled()) {
            LOGGER.info("MCP Server disabled via configuration");
            return;
        }

        LOGGER.info("Starting MCP Server...");

        MCPServer mcpServer = new MCPServer(soundFragmentMCPTools, memoryMCPTools, mcpConfig);

        vertx.deployVerticle(mcpServer)
                .onSuccess(deploymentId -> {
                    LOGGER.info("MCP Server deployed successfully!");
                    LOGGER.info("Deployment ID: {}", deploymentId);
                    LOGGER.info("WebSocket endpoint: ws://{}:{}", mcpConfig.getServerHost(), mcpConfig.getServerPort());
                    LOGGER.info("Available tools: get_brand_soundfragments, search_soundfragments, get_memory_by_type");
                    LOGGER.info("MCP Server ready");
                })
                .onFailure(throwable -> {
                    LOGGER.error("Failed to deploy MCP Server", throwable);
                });
    }
}