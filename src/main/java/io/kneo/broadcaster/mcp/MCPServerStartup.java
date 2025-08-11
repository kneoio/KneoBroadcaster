package io.kneo.broadcaster.mcp;

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

    private String deploymentId;

    void onStart(@Observes StartupEvent ev) {
        if (!mcpConfig.isServerEnabled()) {
            LOGGER.info("MCP Server disabled via configuration");
            return;
        }

        LOGGER.info("Starting MCP Server...");
        LOGGER.info("Target host: {}, port: {}", mcpConfig.getServerHost(), mcpConfig.getServerPort());

        MCPServer mcpServer = new MCPServer(soundFragmentMCPTools, memoryMCPTools, queueMCPTools, mcpConfig);

        vertx.deployVerticle(mcpServer)
                .onSuccess(id -> {
                    this.deploymentId = id;
                    LOGGER.info("MCP Server deployed successfully!");
                    LOGGER.info("Deployment ID: {}", id);
                    LOGGER.info("WebSocket endpoint: ws://{}:{}", mcpConfig.getServerHost(), mcpConfig.getServerPort());
                    LOGGER.info("Available tools: get_brand_soundfragments, search_soundfragments, get_memory_by_type");
                    LOGGER.info("MCP Server ready to accept connections");

                    // Test server responsiveness after deployment
                    testServerConnection();
                })
                .onFailure(throwable -> {
                    LOGGER.error("Failed to deploy MCP Server", throwable);
                    LOGGER.error("Check if port {} is available and not blocked by firewall", mcpConfig.getServerPort());

                    // Log additional troubleshooting info
                    LOGGER.info("Troubleshooting tips:");
                    LOGGER.info("1. Check if another process is using port {}", mcpConfig.getServerPort());
                    LOGGER.info("2. Verify firewall settings allow connections on port {}", mcpConfig.getServerPort());
                    LOGGER.info("3. Ensure {} is a valid bind address", mcpConfig.getServerHost());
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
        // Simple connection test after a brief delay
        vertx.setTimer(2000, id -> {
            vertx.createNetClient()
                    .connect(mcpConfig.getServerPort(), mcpConfig.getServerHost())
                    .onSuccess(socket -> {
                        LOGGER.info("MCP Server connection test successful");
                        socket.close();
                    })
                    .onFailure(throwable -> {
                        LOGGER.warn("MCP Server connection test failed: {}", throwable.getMessage());
                        LOGGER.warn("Server may not be ready yet or there are network issues");
                    });
        });
    }
}