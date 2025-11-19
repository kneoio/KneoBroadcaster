package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.dto.aihelper.SongIntroductionDTO;
import io.kneo.broadcaster.model.cnst.MemoryType;
import io.kneo.broadcaster.service.MemoryService;
import io.kneo.broadcaster.service.live.AiHelperService;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;

@ApplicationScoped
public class AiHelperController {
    private static final Logger LOGGER = LoggerFactory.getLogger(AiHelperController.class);

    private final MemoryService memoryService;
    private final AiHelperService aiHelperService;

    public AiHelperController(MemoryService memoryService, AiHelperService aiHelperService) {
        this.memoryService = memoryService;
        this.aiHelperService = aiHelperService;
    }

    public void setupRoutes(Router router) {
        BodyHandler bodyHandler = BodyHandler.create();
        router.get("/api/ai/memory/:brand").handler(this::getMemoriesByType);
        router.get("/api/ai/messages/:brand/consume").handler(this::consumeInstantMessages);
        router.patch("/api/ai/memory/history/brand/:brand").handler(bodyHandler).handler(this::patchHistory);
        router.patch("/api/ai/memory/reset/:brand/:type").handler(bodyHandler).handler(this::resetMemory);
        router.get("/api/ai/live/stations").handler(this::getLiveRadioStations);
    }

    private void getMemoriesByType(RoutingContext rc) {
        String brand = rc.pathParam("brand");
        if (brand == null || brand.trim().isEmpty()) {
            rc.response()
                    .setStatusCode(400)
                    .putHeader("Content-Type", "text/plain")
                    .end("Brand parameter is required");
            return;
        }

        List<String> typeParams = rc.queryParam("type");
        if (typeParams == null || typeParams.isEmpty()) {
            rc.response()
                    .setStatusCode(400)
                    .putHeader("Content-Type", "text/plain")
                    .end("At least one type query parameter is required");
            return;
        }

        String[] types = typeParams.toArray(new String[0]);

        memoryService.getByType(brand, types)
                .subscribe().with(
                        content -> rc.response()
                                .putHeader("Content-Type", "application/json")
                                .end(Json.encode(content)),
                        throwable -> {
                            LOGGER.error("Error getting memories by brand", throwable);
                            if (throwable instanceof IllegalArgumentException) {
                                rc.response()
                                        .setStatusCode(400)
                                        .putHeader("Content-Type", "text/plain")
                                        .end(throwable.getMessage());
                            } else {
                                rc.response()
                                        .setStatusCode(500)
                                        .putHeader("Content-Type", "text/plain")
                                        .end("An unexpected error occurred retrieving memories.");
                            }
                        }
                );
    }

    private void patchHistory(RoutingContext rc) {
        parsePatchParameters(rc)
                .chain(params -> memoryService.updateHistory(params.brand, params.dto))
                .subscribe().with(
                        doc -> rc.response().setStatusCode(200).end(),
                        throwable -> {
                            LOGGER.error("Error patching memory", throwable);
                            if (throwable instanceof IllegalArgumentException) {
                                rc.response()
                                        .setStatusCode(400)
                                        .putHeader("Content-Type", "text/plain")
                                        .end(throwable.getMessage());
                            } else {
                                rc.response()
                                        .setStatusCode(500)
                                        .putHeader("Content-Type", "text/plain")
                                        .end("An unexpected error occurred updating memory.");
                            }
                        }
                );
    }

    private Uni<PatchParams> parsePatchParameters(RoutingContext rc) {
        return Uni.createFrom().item(() -> {
            String brand = rc.pathParam("brand");
            if (brand == null || brand.trim().isEmpty()) {
                throw new IllegalArgumentException("Brand parameter is required");
            }

            JsonObject jsonObject = rc.body().asJsonObject();
            if (jsonObject == null) {
                throw new IllegalArgumentException("Request body must be a valid JSON object");
            }

            SongIntroductionDTO dto = jsonObject.mapTo(SongIntroductionDTO.class);
            return new PatchParams(brand, dto);
        });
    }

    private record PatchParams(String brand, SongIntroductionDTO dto) {
    }

    private void consumeInstantMessages(RoutingContext rc) {
        String brand = rc.pathParam("brand");
        if (brand == null || brand.trim().isEmpty()) {
            rc.response()
                    .setStatusCode(400)
                    .putHeader("Content-Type", "text/plain")
                    .end("Brand parameter is required");
            return;
        }

        memoryService.retrieveAndRemoveInstantMessages(brand)
                .subscribe().with(
                        messages -> rc.response()
                                .putHeader("Content-Type", "application/json")
                                .end(Json.encode(messages)),
                        throwable -> {
                            LOGGER.error("Error consuming instant messages for brand: {}", brand, throwable);
                            rc.response()
                                    .setStatusCode(500)
                                    .putHeader("Content-Type", "text/plain")
                                    .end("An error occurred while retrieving instant messages");
                        }
                );
    }

    private void resetMemory(RoutingContext rc) {
        String brand = rc.pathParam("brand");
        String type = rc.pathParam("type");

        if (brand == null || brand.trim().isEmpty()) {
            rc.response()
                    .setStatusCode(400)
                    .putHeader("Content-Type", "text/plain")
                    .end("Brand parameter is required");
            return;
        }

        if (type == null || type.trim().isEmpty()) {
            rc.response()
                    .setStatusCode(400)
                    .putHeader("Content-Type", "text/plain")
                    .end("Type parameter is required");
            return;
        }

        List<String> idParams = rc.queryParam("id");
        String memoryId;
        if (idParams != null && !idParams.isEmpty()) {
            memoryId = idParams.get(0);
        } else {
            memoryId = null;
        }

        try {
            MemoryType memoryType = MemoryType.valueOf(type.toUpperCase());

            if (memoryId != null && !memoryId.trim().isEmpty()) {
                memoryService.delete(memoryId.trim())
                        .subscribe().with(
                                removedCount -> rc.response()
                                        .putHeader("Content-Type", "application/json")
                                        .end(Json.encode(new JsonObject().put("removedCount", removedCount))),
                                throwable -> {
                                    LOGGER.error("Error deleting memory by id: {} for brand: {}", memoryId, brand, throwable);
                                    rc.response()
                                            .setStatusCode(500)
                                            .putHeader("Content-Type", "text/plain")
                                            .end("An error occurred while deleting memory by id");
                                }
                        );
            } else {
                memoryService.resetMemory(brand, memoryType)
                        .subscribe().with(
                                removedCount -> rc.response()
                                        .putHeader("Content-Type", "application/json")
                                        .end(Json.encode(new JsonObject().put("removedCount", removedCount))),
                                throwable -> {
                                    LOGGER.error("Error resetting memory for brand: {} and type: {}", brand, type, throwable);
                                    rc.response()
                                            .setStatusCode(500)
                                            .putHeader("Content-Type", "text/plain")
                                            .end("An error occurred while resetting memory");
                                }
                        );
            }
        } catch (IllegalArgumentException e) {
            rc.response()
                    .setStatusCode(400)
                    .putHeader("Content-Type", "text/plain")
                    .end("Invalid memory type. Valid values: " + Arrays.toString(MemoryType.values()));
        }
    }

    private void getLiveRadioStations(RoutingContext rc) {
        aiHelperService.getOnline()
                .subscribe().with(
                        liveContainer -> rc.response()
                                .setStatusCode(200)
                                .putHeader("Content-Type", "application/json")
                                .end(JsonObject.mapFrom(liveContainer).encode()),
                        throwable -> {
                            LOGGER.error("Error getting live radio stations", throwable);
                            rc.response()
                                    .setStatusCode(500)
                                    .putHeader("Content-Type", "text/plain")
                                    .end("An error occurred while retrieving live radio stations");
                        }
                );
    }
}