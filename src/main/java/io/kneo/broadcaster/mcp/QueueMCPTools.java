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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@ApplicationScoped
public class QueueMCPTools {
    private static final Logger LOGGER = LoggerFactory.getLogger(QueueMCPTools.class);

    @Inject
    QueueService service;

    @Inject
    UserService userService;

    public CompletableFuture<JsonObject> addToQueue(
            String brand,
            String songId,
            List<String> filePaths,
            Integer priority
    ) {
        BrandActivityLogger.logActivity(brand, "queue_add",
                "Adding song to queue");

        try {
            IntroPlusSong mergingMethod = new IntroPlusSong();
            mergingMethod.setSoundFragmentUUID(UUID.fromString(songId));
            if (filePaths != null) {
                if (!filePaths.isEmpty()) {
                    // Use first file path for now
                    mergingMethod.setFilePath(Paths.get(filePaths.get(0)));
                }
            }

            AddToQueueDTO dto = new AddToQueueDTO();
            dto.setBrandName(brand);
            dto.setMergingMethod(mergingMethod);
            dto.setPriority(priority);

            return getCurrentUser()
                    .chain(user -> {
                        LOGGER.info("MCP Tool: Got user context: {}", user.getClass().getSimpleName());
                        return service.addToQueue(dto.getBrandName(), dto);
                    })
                    .map(result -> {
                        int filesCount = 0;
                        if (filePaths != null) {
                            filesCount = filePaths.size();
                        }
                        JsonObject response = new JsonObject()
                                .put("success", result)
                                .put("brand", brand)
                                .put("songId", songId)
                                .put("filesProcessed", filesCount);

                        BrandActivityLogger.logActivity(brand, "queue_success",
                                "Successfully added song to queue");
                        LOGGER.info("MCP Tool: Queue addition completed for brand: {}", brand);
                        return response;
                    })
                    .onFailure().invoke(failure -> {
                        BrandActivityLogger.logActivity(brand, "queue_error",
                                "Failed to add to queue: %s", failure.getMessage());
                        LOGGER.error("MCP Tool: Queue addition failed", failure);
                    })
                    .onFailure().recoverWithItem(failure -> {
                        return new JsonObject()
                                .put("success", false)
                                .put("error", failure.getMessage())
                                .put("brand", brand)
                                .put("songId", songId);
                    })
                    .convert().toCompletableFuture();
        } catch (Exception e) {
            LOGGER.error("MCP Tool: Exception in addToQueue", e);
            JsonObject errorResponse = new JsonObject()
                    .put("success", false)
                    .put("error", e.getMessage())
                    .put("brand", brand)
                    .put("songId", songId);
            return CompletableFuture.completedFuture(errorResponse);
        }
    }

    private Uni<IUser> getCurrentUser() {
        return Uni.createFrom().item(io.kneo.core.model.user.SuperUser.build());
    }
}