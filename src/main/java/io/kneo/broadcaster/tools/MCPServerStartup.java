package io.kneo.broadcaster.tools;

import io.kneo.broadcaster.config.MCPConfig;
import io.quarkus.runtime.ShutdownEvent;
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

    @Inject
    QueueMCPTools queueMCPTools;

    @Inject
    LiveRadioStationsMCPTools liveRadioStationsMCPTools;

    private String deploymentId;

    void onStart(@Observes StartupEvent ev) {
        if (!mcpConfig.isServerEnabled()) {
            LOGGER.info("MCP Server disabled via configuration");
            return;
        }

        LOGGER.info("Starting MCP Server...");
        LOGGER.info("Target host: {}, port: {}", mcpConfig.getServerHost(), mcpConfig.getServerPort());

        MCPServer mcpServer = new MCPServer(soundFragmentMCPTools, memoryMCPTools, queueMCPTools, liveRadioStationsMCPTools, mcpConfig);

        vertx.deployVerticle(mcpServer)
                .onSuccess(id -> {
                    this.deploymentId = id;
                    LOGGER.info("MCP Server deployed");
                    LOGGER.info("Deployment ID: {}", id);
                    LOGGER.info("WebSocket endpoint: ws://{}:{}", mcpConfig.getServerHost(), mcpConfig.getServerPort());
                    LOGGER.info("Available tools: get_brand_sound_fragment, get_memory_by_type, add_to_queue, get_live_radio_stations");
                    LOGGER.info("MCP Server ready to accept connections");

                    testServerConnection();
                })
                .onFailure(throwable -> {
                    LOGGER.error("Failed to deploy MCP Server", throwable);
                    LOGGER.error("Port: {}", mcpConfig.getServerPort());
                });
    }

    void onStop(@Observes ShutdownEvent ev) {
        if (deploymentId != null) {
            LOGGER.info("Shutting down MCP Server...");
            vertx.undeploy(deploymentId)
                    .onSuccess(v -> LOGGER.info("MCP Server shutdown complete"))
                    .onFailure(throwable -> LOGGER.error("Error during MCP Server shutdown", throwable));
        }
    }

    private void testServerConnection() {
        vertx.setTimer(2000, id -> {
            vertx.createNetClient()
                    .connect(mcpConfig.getServerPort(), mcpConfig.getServerHost())
                    .onSuccess(socket -> {
                        LOGGER.info("MCP Server connection test successful");
                        socket.close();
                    })
                    .onFailure(throwable -> {
                        LOGGER.warn("MCP Server connection test failed: {}", throwable.getMessage());
                    });
        });
    }
}