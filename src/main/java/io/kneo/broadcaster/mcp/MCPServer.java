package io.kneo.broadcaster.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.kneo.broadcaster.config.MCPConfig;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.ServerWebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class MCPServer extends AbstractVerticle {
    private static final Logger LOGGER = LoggerFactory.getLogger(MCPServer.class);

    private final SoundFragmentMCPTools soundFragmentMCPTools;
    private final MemoryMCPTools memoryMCPTools;
    private final QueueMCPTools queueMCPTools;
    private final MCPConfig mcpConfig;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());
    private HttpServer server;

    public MCPServer(SoundFragmentMCPTools soundFragmentMCPTools, MemoryMCPTools memoryMCPTools, QueueMCPTools queueMCPTools, MCPConfig mcpConfig) {
        this.soundFragmentMCPTools = soundFragmentMCPTools;
        this.memoryMCPTools = memoryMCPTools;
        this.queueMCPTools = queueMCPTools;
        this.mcpConfig = mcpConfig;
    }

    @Override
    public void start(Promise<Void> startPromise) {
        try {
            server = vertx.createHttpServer();
            server.webSocketHandler(webSocket -> {
                        LOGGER.info("WebSocket connection attempt: path={}, remote={}",
                                webSocket.path(), webSocket.remoteAddress());

                        handleWebSocket(webSocket);
                    })
                    .listen(mcpConfig.getServerPort(), mcpConfig.getServerHost())
                    .onSuccess(result -> {
                        LOGGER.info("MCP Server started on {}:{}", mcpConfig.getServerHost(), mcpConfig.getServerPort());
                        startPromise.complete();
                    })
                    .onFailure(throwable -> {
                        LOGGER.error("Failed to start MCP Server on {}:{}",
                                mcpConfig.getServerHost(), mcpConfig.getServerPort(), throwable);
                        startPromise.fail(throwable);
                    });
        } catch (Exception e) {
            LOGGER.error("Error during MCP Server startup", e);
            startPromise.fail(e);
        }
    }

    private void handleWebSocket(ServerWebSocket webSocket) {
        try {
            LOGGER.info("WebSocket handshake initiated from: {}", webSocket.remoteAddress());
            LOGGER.info("WebSocket path: {}", webSocket.path());
            LOGGER.info("WebSocket headers: {}", webSocket.headers());

            LOGGER.info("MCP client connected from: {}", webSocket.remoteAddress());
            LOGGER.info("Connection established, ready to receive MCP requests");

            webSocket.textMessageHandler(message -> {
                try {
                    LOGGER.debug("Received MCP message: {}", message);
                    JsonNode request = objectMapper.readTree(message);
                    handleMCPRequest(webSocket, request);
                } catch (Exception e) {
                    LOGGER.error("Error processing MCP request: {}", e.getMessage(), e);
                    sendError(webSocket, "parse_error", "Invalid JSON", null);
                }
            });

            webSocket.closeHandler(v -> {
                LOGGER.info("MCP client disconnected: {}", webSocket.remoteAddress());
            });

            webSocket.exceptionHandler(throwable -> {
                LOGGER.error("WebSocket error for client {}: {}", webSocket.remoteAddress(), throwable.getMessage(), throwable);
            });
        } catch (Exception e) {
            LOGGER.error("Error handling WebSocket connection", e);
            webSocket.close();
        }
    }

    private void handleMCPRequest(ServerWebSocket webSocket, JsonNode request) {
        try {
            String method = request.get("method").asText();
            JsonNode params = request.get("params");
            String id = request.has("id") ? request.get("id").asText() : null;

            LOGGER.info("Processing MCP request: method='{}', id='{}'", method, id);

            switch (method) {
                case "initialize":
                    LOGGER.info("Handling initialize request");
                    handleInitialize(webSocket, id);
                    break;
                case "tools/list":
                    LOGGER.info("Handling tools/list request");
                    handleToolsList(webSocket, id);
                    break;
                case "tools/call":
                    String toolName = params != null && params.has("name") ? params.get("name").asText() : "unknown";
                    LOGGER.info("Handling tools/call request for tool: '{}'", toolName);
                    handleToolCall(webSocket, params, id);
                    break;
                default:
                    LOGGER.warn("Unknown method requested: '{}'", method);
                    sendError(webSocket, "method_not_found", "Method not found: " + method, id);
            }
        } catch (Exception e) {
            LOGGER.error("Error handling MCP request: {}", e.getMessage(), e);
            sendError(webSocket, "internal_error", "Internal server error", null);
        }
    }

    private void handleInitialize(ServerWebSocket webSocket, String id) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.put("id", id);

        ObjectNode result = objectMapper.createObjectNode();
        result.put("protocolVersion", mcpConfig.getProtocolVersion());
        ObjectNode serverInfo = objectMapper.createObjectNode();
        serverInfo.put("name", mcpConfig.getServerName());
        serverInfo.put("version", mcpConfig.getServerVersion());
        result.set("serverInfo", serverInfo);

        ObjectNode capabilities = objectMapper.createObjectNode();
        ObjectNode tools = objectMapper.createObjectNode();
        tools.put("listChanged", true);
        capabilities.set("tools", tools);
        result.set("capabilities", capabilities);

        response.set("result", result);

        webSocket.writeTextMessage(response.toString());
    }

    private void handleToolsList(ServerWebSocket webSocket, String id) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.put("id", id);

        ArrayNode tools = objectMapper.createArrayNode();
        tools.add(createBrandSoundFragmentsTool());
        tools.add(createMemoryTool());
        tools.add(createAddToQueueTool());

        ObjectNode result = objectMapper.createObjectNode();
        result.set("tools", tools);
        response.set("result", result);

        webSocket.writeTextMessage(response.toString());
    }

    private ObjectNode createBrandSoundFragmentsTool() {
        ObjectNode tool = objectMapper.createObjectNode();
        tool.put("name", "get_brand_sound_fragment");
        tool.put("description", "Get a single song for a specific brand filtered by playlist item type. Returns null if no songs found.");

        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = objectMapper.createObjectNode();

        addStringProperty(props, "brand", "Brand name to get song for");
        addStringProperty(props, "fragment_type", "Playlist item type (must be valid PlaylistItemType enum value)");

        schema.set("properties", props);
        ArrayNode required = objectMapper.createArrayNode();
        required.add("brand");
        required.add("fragment_type");
        schema.set("required", required);
        tool.set("inputSchema", schema);

        return tool;
    }

    private CompletableFuture<Object> handleBrandSoundFragmentsCall(JsonNode arguments) {
        String brand = arguments.get("brand").asText();
        String fragmentType = arguments.get("fragment_type").asText();

        return soundFragmentMCPTools.getBrandSoundFragments(brand, fragmentType)
                .thenApply(result -> (Object) result);
    }

    private ObjectNode createMemoryTool() {
        ObjectNode tool = objectMapper.createObjectNode();
        tool.put("name", "get_memory_by_type");
        tool.put("description", "Get memory data by type for a specific brand");

        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = objectMapper.createObjectNode();

        addStringProperty(props, "brand", "Brand name to filter memory by");

        ObjectNode typesProp = objectMapper.createObjectNode();
        typesProp.put("type", "array");
        ObjectNode itemsProp = objectMapper.createObjectNode();
        itemsProp.put("type", "string");
        typesProp.set("items", itemsProp);
        typesProp.put("description", "Memory types to retrieve (CONVERSATION_HISTORY, LISTENER_CONTEXT, AUDIENCE_CONTEXT, INSTANT_MESSAGE, EVENT)");
        props.set("types", typesProp);

        schema.set("properties", props);
        ArrayNode required = objectMapper.createArrayNode();
        required.add("brand");
        required.add("types");
        schema.set("required", required);
        tool.set("inputSchema", schema);

        return tool;
    }

    private ObjectNode createAddToQueueTool() {
        ObjectNode tool = objectMapper.createObjectNode();
        tool.put("name", "add_to_queue");
        tool.put("description", "Add a song to the queue for a specific brand");

        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = objectMapper.createObjectNode();

        addStringProperty(props, "brand", "Brand name");
        addStringProperty(props, "songId", "UUID of the song to add to queue");

        ObjectNode filePathsProp = objectMapper.createObjectNode();
        filePathsProp.put("type", "array");
        ObjectNode itemsProp = objectMapper.createObjectNode();
        itemsProp.put("type", "string");
        filePathsProp.set("items", itemsProp);
        filePathsProp.put("description", "List of file paths associated with the song");
        props.set("filePaths", filePathsProp);

        addIntegerProperty(props, "priority", "Priority level for queue ordering", null);

        ObjectNode metadataProp = objectMapper.createObjectNode();
        metadataProp.put("type", "object");
        metadataProp.put("description", "Additional metadata for the song");
        props.set("metadata", metadataProp);

        schema.set("properties", props);
        ArrayNode required = objectMapper.createArrayNode();
        required.add("brand");
        required.add("songId");
        schema.set("required", required);
        tool.set("inputSchema", schema);

        return tool;
    }

    private void addStringProperty(ObjectNode props, String name, String description) {
        ObjectNode prop = objectMapper.createObjectNode();
        prop.put("type", "string");
        prop.put("description", description);
        props.set(name, prop);
    }

    private void addIntegerProperty(ObjectNode props, String name, String description, Integer defaultValue) {
        ObjectNode prop = objectMapper.createObjectNode();
        prop.put("type", "integer");
        prop.put("description", description);
        if (defaultValue != null) {
            prop.put("default", defaultValue);
        }
        props.set(name, prop);
    }

    private void handleToolCall(ServerWebSocket webSocket, JsonNode params, String id) {
        try {
            String toolName = params.get("name").asText();
            JsonNode arguments = params.get("arguments");

            CompletableFuture<Object> future;

            switch (toolName) {
                case "get_brand_sound_fragment":
                    future = handleBrandSoundFragmentsCall(arguments);
                    break;
                case "get_memory_by_type":
                    future = handleMemoryCall(arguments);
                    break;
                case "add_to_queue":
                    future = handleAddToQueueCall(arguments);
                    break;


                default:
                    sendError(webSocket, "tool_not_found", "Tool not found: " + toolName, id);
                    return;
            }

            future.whenComplete((result, throwable) -> {
                if (throwable != null) {
                    LOGGER.error("Error executing tool: {}", toolName, throwable);
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

    private CompletableFuture<Object> handleMemoryCall(JsonNode arguments) {
        String brand = arguments.get("brand").asText();
        JsonNode typesNode = arguments.get("types");
        List<String> typesList = new ArrayList<>();
        if (typesNode.isArray()) {
            for (JsonNode typeNode : typesNode) {
                typesList.add(typeNode.asText());
            }
        }
        String[] types = typesList.toArray(new String[0]);

        return memoryMCPTools.getMemoryByType(brand, types)
                .thenApply(result -> (Object) result);
    }

    private CompletableFuture<Object> handleAddToQueueCall(JsonNode arguments) {
        String brand = null;
        if (arguments.has("brand")) {
            brand = arguments.get("brand").asText();
        }

        String songId = null;
        if (arguments.has("songId")) {
            songId = arguments.get("songId").asText();
        }

        List<String> filePaths = new ArrayList<>();
        if (arguments.has("filePaths")) {
            if (arguments.get("filePaths").isArray()) {
                ArrayNode filePathsArray = (ArrayNode) arguments.get("filePaths");
                for (JsonNode filePathNode : filePathsArray) {
                    filePaths.add(filePathNode.asText());
                }
            }
        }

        Integer priority = null;
        if (arguments.has("priority")) {
            priority = arguments.get("priority").asInt();
        }

        return queueMCPTools.addToQueue(brand, songId, filePaths, priority)
                .thenApply(result -> (Object) result);
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
        try {
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
        } catch (Exception e) {
            LOGGER.error("Failed to send error response", e);
        }
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
}