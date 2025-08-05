package io.kneo.broadcaster.mcp;

import io.kneo.broadcaster.dto.queue.AddToQueueDTO;
import io.kneo.broadcaster.dto.queue.IntroPlusSong;
import io.kneo.broadcaster.service.QueueService;
import io.kneo.broadcaster.util.BrandActivityLogger;
import io.kneo.core.model.user.IUser;
import io.kneo.core.service.UserService;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Paths;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@ApplicationScoped
public class QueueMCPTools {
    private static final Logger LOGGER = LoggerFactory.getLogger(QueueMCPTools.class);

    @Inject
    QueueService service;

    @Inject
    UserService userService;

    @Tool("add_to_queue")
    @Description("Add a song to the queue for a specific brand")
    public CompletableFuture<JsonObject> addToQueue(
            @Parameter("brandName") String brandName,
            @Parameter("soundFragmentUUID") String soundFragmentUUID,
            @Parameter("filePath") String filePath,
            @Parameter("priority") Integer priority
    ) {
        BrandActivityLogger.logActivity(brandName, "queue_add",
                "Adding song to queue");

        IntroPlusSong mergingMethod = new IntroPlusSong();
        mergingMethod.setSoundFragmentUUID(UUID.fromString(soundFragmentUUID));
        mergingMethod.setFilePath(Paths.get(filePath));

        AddToQueueDTO dto = new AddToQueueDTO();
        dto.setBrandName(brandName);
        dto.setMergingMethod(mergingMethod);
        dto.setPriority(priority);

        return getCurrentUser()
                .chain(user -> {
                    LOGGER.info("MCP Tool: Got user context: {}", user.getClass().getSimpleName());
                    return service.addToQueue(dto.getBrandName(), dto);
                })
                .map(result -> {
                    JsonObject response = new JsonObject()
                            .put("success", result)
                            .put("brand", brandName);

                    BrandActivityLogger.logActivity(brandName, "queue_success",
                            "Successfully added song to queue");
                    LOGGER.info("MCP Tool: Queue addition completed for brand: {}", brandName);
                    return response;
                })
                .onFailure().invoke(failure -> {
                    BrandActivityLogger.logActivity(brandName, "queue_error",
                            "Failed to add to queue: %s", failure.getMessage());
                    LOGGER.error("MCP Tool: Queue addition failed", failure);
                })
                .onFailure().recoverWithItem(failure -> {
                    return new JsonObject()
                            .put("success", false)
                            .put("error", failure.getMessage());
                })
                .convert().toCompletableFuture();
    }

    @Tool("get_queue")
    @Description("Get the current queue for a specific brand")
    public CompletableFuture<JsonObject> getQueue(
            @Parameter("brand") String brandName
    ) {
        BrandActivityLogger.logActivity(brandName, "queue_get",
                "Fetching queue for brand");

        return getCurrentUser()
                .chain(user -> {
                    LOGGER.info("MCP Tool: Got user context: {}", user.getClass().getSimpleName());
                    return service.getQueueForBrand(brandName);
                })
                .map(items -> {
                    JsonObject response = new JsonObject()
                            .put("brand", brandName)
                            .put("queue", items)
                            .put("count", items.size());

                    BrandActivityLogger.logActivity(brandName, "queue_get_success",
                            "Retrieved %d items from queue", items.size());
                    LOGGER.info("MCP Tool: Queue retrieval completed for brand: {}", brandName);
                    return response;
                })
                .onFailure().invoke(failure -> {
                    BrandActivityLogger.logActivity(brandName, "queue_get_error",
                            "Failed to get queue: %s", failure.getMessage());
                    LOGGER.error("MCP Tool: Queue retrieval failed", failure);
                })
                .convert().toCompletableFuture();
    }

    private Uni<IUser> getCurrentUser() {
        return Uni.createFrom().item(io.kneo.core.model.user.SuperUser.build());
    }
}