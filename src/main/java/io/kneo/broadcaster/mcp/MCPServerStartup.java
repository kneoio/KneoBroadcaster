package io.kneo.broadcaster.mcp;

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
    MCPServer mcpServer;
    
    void onStart(@Observes StartupEvent ev) {
        LOGGER.info("Starting MCP Server...");
        
        vertx.deployVerticle(mcpServer)
            .onSuccess(deploymentId -> {
                LOGGER.info("MCP Server deployed successfully with ID: {}", deploymentId);
            })
            .onFailure(throwable -> {
                LOGGER.error("Failed to deploy MCP Server", throwable);
            });
    }
}
