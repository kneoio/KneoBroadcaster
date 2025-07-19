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
    SoundFragmentMCPTools mcpTools;
    
    void onStart(@Observes StartupEvent ev) {
        LOGGER.info("Starting MCP Server...");
        
        MCPServer mcpServer = new MCPServer(mcpTools);
        
        vertx.deployVerticle(mcpServer)
            .onSuccess(deploymentId -> {
                LOGGER.info("MCP Server deployed successfully!");
                LOGGER.info("Deployment ID: {}", deploymentId);
                LOGGER.info("WebSocket endpoint: ws://localhost:38708");
                LOGGER.info("Available tools: get_brand_soundfragments, search_soundfragments");
                LOGGER.info("MCP Server ready");
            })
            .onFailure(throwable -> {
                LOGGER.error("Failed to deploy MCP Server", throwable);
            });
    }
}
