package io.kneo.broadcaster.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.ServerWebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class MCPServer extends AbstractVerticle {
    private static final Logger LOGGER = LoggerFactory.getLogger(MCPServer.class);
    private static final int MCP_PORT = 38708; 
    
    private SoundFragmentMCPTools mcpTools;
    
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());
    private HttpServer server;
    
    public MCPServer(SoundFragmentMCPTools mcpTools) {
        this.mcpTools = mcpTools;
    }
    
    @Override
    public void start(Promise<Void> startPromise) {
        server = vertx.createHttpServer();
        
        server.webSocketHandler(this::handleWebSocket)
              .listen(MCP_PORT)
              .onSuccess(result -> {
                  LOGGER.info("MCP Server started on port {}", MCP_PORT);
                  startPromise.complete();
              })
              .onFailure(startPromise::fail);
    }
    
    private void handleWebSocket(ServerWebSocket webSocket) {
        LOGGER.info("🔗 MCP client connected from: {}", webSocket.remoteAddress());
        LOGGER.info("   Connection established, ready to receive MCP requests");
        
        webSocket.textMessageHandler(message -> {
            try {
                LOGGER.debug("📨 Received MCP message: {}", message);
                JsonNode request = objectMapper.readTree(message);
                handleMCPRequest(webSocket, request);
            } catch (Exception e) {
                LOGGER.error("❌ Error processing MCP request: {}", e.getMessage(), e);
                sendError(webSocket, "parse_error", "Invalid JSON", null);
            }
        });
        
        webSocket.closeHandler(v -> {
            LOGGER.info("🔌 MCP client disconnected: {}", webSocket.remoteAddress());
        });
        
        webSocket.exceptionHandler(throwable -> {
            LOGGER.error("⚠️ WebSocket error for client {}: {}", webSocket.remoteAddress(), throwable.getMessage(), throwable);
        });
    }
    
    private void handleMCPRequest(ServerWebSocket webSocket, JsonNode request) {
        try {
            String method = request.get("method").asText();
            JsonNode params = request.get("params");
            String id = request.has("id") ? request.get("id").asText() : null;
            
            LOGGER.info("🔧 Processing MCP request: method='{}', id='{}'", method, id);
            
            switch (method) {
                case "initialize":
                    LOGGER.info("   📋 Handling initialize request");
                    handleInitialize(webSocket, id);
                    break;
                case "tools/list":
                    LOGGER.info("   📝 Handling tools/list request");
                    handleToolsList(webSocket, id);
                    break;
                case "tools/call":
                    String toolName = params != null && params.has("name") ? params.get("name").asText() : "unknown";
                    LOGGER.info("   🛠️ Handling tools/call request for tool: '{}'", toolName);
                    handleToolCall(webSocket, params, id);
                    break;
                default:
                    LOGGER.warn("   ❓ Unknown method requested: '{}'", method);
                    sendError(webSocket, "method_not_found", "Method not found: " + method, id);
            }
        } catch (Exception e) {
            LOGGER.error("💥 Error handling MCP request: {}", e.getMessage(), e);
            sendError(webSocket, "internal_error", "Internal server error", null);
        }
    }
    
    private void handleInitialize(ServerWebSocket webSocket, String id) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        
        ObjectNode result = objectMapper.createObjectNode();
        result.put("protocolVersion", "2024-11-05");
        result.put("serverInfo", objectMapper.createObjectNode()
            .put("name", "KneoBroadcaster-MCP")
            .put("version", "1.0.0"));
        
        ObjectNode capabilities = objectMapper.createObjectNode();
        capabilities.put("tools", objectMapper.createObjectNode());
        result.set("capabilities", capabilities);
        
        response.set("result", result);
        
        webSocket.writeTextMessage(response.toString());
    }
    
    private void handleToolsList(ServerWebSocket webSocket, String id) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        
        ArrayNode tools = objectMapper.createArrayNode();
        
        // Add get_brand_soundfragments tool
        ObjectNode brandTool = objectMapper.createObjectNode();
        brandTool.put("name", "get_brand_soundfragments");
        brandTool.put("description", "Get sound fragments available for a specific brand");
        
        ObjectNode brandSchema = objectMapper.createObjectNode();
        brandSchema.put("type", "object");
        ObjectNode brandProps = objectMapper.createObjectNode();
        brandProps.set("brand", objectMapper.createObjectNode()
            .put("type", "string")
            .put("description", "Brand name to filter sound fragments by"));
        brandProps.set("page", objectMapper.createObjectNode()
            .put("type", "integer")
            .put("description", "Page number for pagination (1-based)")
            .put("default", 1));
        brandProps.set("size", objectMapper.createObjectNode()
            .put("type", "integer")
            .put("description", "Number of items per page")
            .put("default", 10));
        brandSchema.set("properties", brandProps);
        ArrayNode brandRequired = objectMapper.createArrayNode();
        brandRequired.add("brand");
        brandSchema.set("required", brandRequired);
        brandTool.set("inputSchema", brandSchema);
        
        tools.add(brandTool);
        
        // Add search_soundfragments tool
        ObjectNode searchTool = objectMapper.createObjectNode();
        searchTool.put("name", "search_soundfragments");
        searchTool.put("description", "Search sound fragments by query term");
        
        ObjectNode searchSchema = objectMapper.createObjectNode();
        searchSchema.put("type", "object");
        ObjectNode searchProps = objectMapper.createObjectNode();
        searchProps.set("query", objectMapper.createObjectNode()
            .put("type", "string")
            .put("description", "Search term to find matching sound fragments"));
        searchProps.set("page", objectMapper.createObjectNode()
            .put("type", "integer")
            .put("description", "Page number for pagination (1-based)")
            .put("default", 1));
        searchProps.set("size", objectMapper.createObjectNode()
            .put("type", "integer")
            .put("description", "Number of items per page")
            .put("default", 10));
        searchSchema.set("properties", searchProps);
        ArrayNode searchRequired = objectMapper.createArrayNode();
        searchRequired.add("query");
        searchSchema.set("required", searchRequired);
        searchTool.set("inputSchema", searchSchema);
        
        tools.add(searchTool);
        
        ObjectNode result = objectMapper.createObjectNode();
        result.set("tools", tools);
        response.set("result", result);
        
        webSocket.writeTextMessage(response.toString());
    }
    
    private void handleToolCall(ServerWebSocket webSocket, JsonNode params, String id) {
        try {
            String toolName = params.get("name").asText();
            JsonNode arguments = params.get("arguments");
            
            CompletableFuture<Object> future;
            
            switch (toolName) {
                case "get_brand_soundfragments":
                    String brand = arguments.get("brand").asText();
                    Optional<Integer> brandPage = arguments.has("page") ? 
                        Optional.of(arguments.get("page").asInt()) : Optional.empty();
                    Optional<Integer> brandSize = arguments.has("size") ? 
                        Optional.of(arguments.get("size").asInt()) : Optional.empty();
                    
                    future = mcpTools.getBrandSoundFragments(brand, brandPage, brandSize)
                        .thenApply(result -> (Object) result);
                    break;
                    
                case "search_soundfragments":
                    String query = arguments.get("query").asText();
                    Optional<Integer> searchPage = arguments.has("page") ? 
                        Optional.of(arguments.get("page").asInt()) : Optional.empty();
                    Optional<Integer> searchSize = arguments.has("size") ? 
                        Optional.of(arguments.get("size").asInt()) : Optional.empty();
                    
                    future = mcpTools.searchSoundFragments(query, searchPage, searchSize)
                        .thenApply(result -> (Object) result);
                    break;
                    
                default:
                    sendError(webSocket, "tool_not_found", "Tool not found: " + toolName, id);
                    return;
            }
            
            future.whenComplete((result, throwable) -> {
                if (throwable != null) {
                    LOGGER.error("Error executing tool: " + toolName, throwable);
                    sendError(webSocket, "tool_error", throwable.getMessage(), id);
                } else {
                    sendToolResult(webSocket, result, id);
                }
            });
            
        } catch (Exception e) {
            LOGGER.error("Error processing tool call", e);
            sendError(webSocket, "invalid_params", "Invalid tool call parameters", id);
        }
    }
    
    private void sendToolResult(ServerWebSocket webSocket, Object result, String id) {
        try {
            ObjectNode response = objectMapper.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.put("id", id);
            
            ObjectNode resultNode = objectMapper.createObjectNode();
            ArrayNode content = objectMapper.createArrayNode();
            
            ObjectNode textContent = objectMapper.createObjectNode();
            textContent.put("type", "text");
            textContent.put("text", objectMapper.writeValueAsString(result));
            content.add(textContent);
            
            resultNode.set("content", content);
            response.set("result", resultNode);
            
            webSocket.writeTextMessage(response.toString());
        } catch (Exception e) {
            LOGGER.error("Error sending tool result", e);
            sendError(webSocket, "internal_error", "Error serializing result", id);
        }
    }
    
    private void sendError(ServerWebSocket webSocket, String code, String message, String id) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (id != null) {
            response.put("id", id);
        }
        
        ObjectNode error = objectMapper.createObjectNode();
        error.put("code", code);
        error.put("message", message);
        response.set("error", error);
        
        webSocket.writeTextMessage(response.toString());
    }
    
    @Override
    public void stop(Promise<Void> stopPromise) {
        if (server != null) {
            server.close().onComplete(result -> {
                LOGGER.info("MCP Server stopped");
                stopPromise.complete();
            });
        } else {
            stopPromise.complete();
        }
    }
    
    public void startServer() {
        vertx.deployVerticle(this);
    }
}
