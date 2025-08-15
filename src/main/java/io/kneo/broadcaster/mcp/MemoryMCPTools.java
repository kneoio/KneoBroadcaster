package io.kneo.broadcaster.mcp;

import io.kneo.broadcaster.service.MemoryService;
import io.kneo.core.model.user.IUser;
import io.kneo.core.service.UserService;
import io.kneo.broadcaster.util.BrandActivityLogger;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

@ApplicationScoped
public class MemoryMCPTools {
    private static final Logger LOGGER = LoggerFactory.getLogger(MemoryMCPTools.class);

    @Inject
    MemoryService service;

    @Inject
    UserService userService;

    @Tool("get_memory_by_type")
    @Description("Get memory data by type for a specific brand")
    public CompletableFuture<JsonObject> getMemoryByType(
            @Parameter("brand") String brandName,
            @Parameter("types") String... types
    ) {
        BrandActivityLogger.logActivity(brandName, "memory_query",
                "Fetching memory types: %s", String.join(", ", types));

        return getCurrentUser()
                .chain(user -> {
                    LOGGER.info("MCP Tool: Got user context: {}", user.getClass().getSimpleName());
                    return service.getByType(brandName, types);
                })
                .map(result -> {
                    BrandActivityLogger.logActivity(brandName, "memory_results",
                            "Retrieved memory data for %d types", types.length);
                    LOGGER.info("MCP Tool: Memory retrieval completed for brand: {}", brandName);

                    // Wrap the result in the expected format
                    JsonObject wrappedResult = new JsonObject();
                    JsonObject mapData = new JsonObject();

                    for (String key : result.fieldNames()) {
                        JsonArray data = result.getJsonArray(key);
                        JsonObject typeData = new JsonObject()
                                .put("list", data);
                        mapData.put(key, typeData);
                    }

                    wrappedResult.put("map", mapData);

                    return wrappedResult;
                })
                .onFailure().invoke(failure -> {
                    BrandActivityLogger.logActivity(brandName, "memory_error",
                            "Failed to get memory: %s", failure.getMessage());
                    LOGGER.error("MCP Tool: Memory retrieval failed", failure);
                })
                .convert().toCompletableFuture();
    }

    private Uni<IUser> getCurrentUser() {
        return Uni.createFrom().item(io.kneo.core.model.user.SuperUser.build());
    }
}