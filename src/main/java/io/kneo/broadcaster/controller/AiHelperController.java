package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.dto.ConversationMemoryDTO;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.service.AiHelperService;
import io.kneo.broadcaster.service.MemoryService;
import io.kneo.core.model.user.SuperUser;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class AiHelperController {

    private final AiHelperService aiHelperService;
    private final MemoryService memoryService;

    @Inject
    public AiHelperController(AiHelperService aiHelperService, MemoryService memoryService) {
        this.aiHelperService = aiHelperService;
        this.memoryService = memoryService;
    }

    public void setupRoutes(Router router) {
        router.route("/api/ai/*").handler(BodyHandler.create());

        router.get("/api/ai/brands/status").handler(this::handleGetBrandsByStatus);

        router.get("/api/ai/memory/:brand").handler(this::handleGetMemoriesByBrand);
        router.post("/api/ai/memory/:id?").handler(this::handleUpsertMemory);
        router.delete("/api/ai/memory/:id").handler(this::handleDeleteMemory);
        router.delete("/api/ai/memory/brand/:brand").handler(this::handleDeleteMemoriesByBrand);
    }

    private void handleGetBrandsByStatus(RoutingContext rc) {
        List<String> statusParams = rc.queryParam("status");

        if (statusParams == null || statusParams.isEmpty()) {
            rc.response()
                    .setStatusCode(400)
                    .putHeader("Content-Type", "text/plain")
                    .end("At least one status query parameter is required. Valid values: " +
                            Arrays.toString(RadioStationStatus.values()));
            return;
        }

        try {
            List<RadioStationStatus> statuses = statusParams.stream()
                    .map(String::toUpperCase)
                    .map(RadioStationStatus::valueOf)
                    .collect(Collectors.toList());

            aiHelperService.getByStatus(statuses)
                    .subscribe().with(
                            brands -> rc.response()
                                    .putHeader("Content-Type", "application/json")
                                    .end(Json.encode(brands)),
                            failure -> rc.response()
                                    .setStatusCode(400) // Or 500 depending on expected failures
                                    .putHeader("Content-Type", "text/plain")
                                    .end(failure.getMessage())
                    );
        } catch (IllegalArgumentException e) {
            rc.response()
                    .setStatusCode(400)
                    .putHeader("Content-Type", "text/plain")
                    .end("Invalid status value provided. Valid values: " +
                            Arrays.toString(RadioStationStatus.values()));
        }
    }

    private void handleGetMemoriesByBrand(RoutingContext rc) {
        try {
            String brand = rc.pathParam("brand");
            int limit = rc.queryParam("limit").isEmpty() ? 10 : Integer.parseInt(rc.queryParam("limit").get(0));
            int offset = rc.queryParam("offset").isEmpty() ? 0 : Integer.parseInt(rc.queryParam("offset").get(0));


            memoryService.getByBrandId(brand, limit, offset)
                    .subscribe().with(
                            memories -> rc.response()
                                    .putHeader("Content-Type", "application/json")
                                    .end(Json.encode(memories)),
                            failure -> rc.response()
                                    .setStatusCode(404)
                                    .putHeader("Content-Type", "text/plain")
                                    .end(failure.getMessage())
                    );
        } catch (NumberFormatException e) {
            rc.response()
                    .setStatusCode(400)
                    .putHeader("Content-Type", "text/plain")
                    .end("Invalid format for 'limit' or 'offset' query parameters.");
        } catch (Exception e) { // Catching potential NPE or other issues
            rc.response()
                    .setStatusCode(500)
                    .putHeader("Content-Type", "text/plain")
                    .end("An unexpected error occurred retrieving memories.");
        }
    }

    private void handleDeleteMemoriesByBrand(RoutingContext rc) {
        try {
            String brand = rc.pathParam("brand");

            memoryService.deleteByBrand(brand)
                    .subscribe().with(
                            deletedCount -> {
                                JsonObject response = new JsonObject()
                                        .put("deletedCount", deletedCount)
                                        .put("brand", brand);
                                rc.response()
                                        .setStatusCode(200)
                                        .putHeader("Content-Type", "application/json")
                                        .end(response.encode());
                            },
                            failure -> rc.response()
                                    .setStatusCode(400)
                                    .putHeader("Content-Type", "text/plain")
                                    .end(failure.getMessage())
                    );
        } catch (Exception e) {
            rc.response()
                    .setStatusCode(500)
                    .putHeader("Content-Type", "text/plain")
                    .end("An unexpected error occurred while deleting memories by brand: " + e.getMessage());
        }
    }

    private void handleUpsertMemory(RoutingContext rc) {
        String idParam = rc.pathParam("id");
        UUID id = null;
        boolean isUpdate = idParam != null;

        try {
            if (isUpdate) {
                id = UUID.fromString(idParam);
            }

            JsonObject jsonObject = rc.body().asJsonObject();
            if (jsonObject == null) {
                rc.response().setStatusCode(400).putHeader("Content-Type", "text/plain").end("Request body cannot be empty.");
                return;
            }
            ConversationMemoryDTO dto = jsonObject.mapTo(ConversationMemoryDTO.class);

            Uni<ConversationMemoryDTO> upsertOperation = memoryService.upsert(id, dto, SuperUser.build());

            upsertOperation.subscribe().with(
                    savedMemory -> rc.response()
                            .setStatusCode(isUpdate ? 200 : 201)
                            .putHeader("Content-Type", "application/json")
                            .end(Json.encode(savedMemory)),
                    failure -> rc.response()
                            .setStatusCode(400)
                            .putHeader("Content-Type", "text/plain")
                            .end(failure.getMessage())
            );

        } catch (IllegalArgumentException e) {
            rc.response()
                    .setStatusCode(400)
                    .putHeader("Content-Type", "text/plain")
                    .end("Invalid ID format. Must be a valid UUID.");
        } catch (Exception e) {
            rc.response()
                    .setStatusCode(400)
                    .putHeader("Content-Type", "text/plain")
                    .end("Invalid request data: " + e.getMessage());
        }
    }

    private void handleDeleteMemory(RoutingContext rc) {
        String idParam = rc.pathParam("id");
        try {
            UUID id = UUID.fromString(idParam);
            memoryService.delete(id)
                    .subscribe().with(
                            deleted -> rc.response()
                                    .setStatusCode(204)
                                    .end(),
                            failure -> rc.response()
                                    .setStatusCode(400)
                                    .putHeader("Content-Type", "text/plain")
                                    .end(failure.getMessage())
                    );
        } catch (IllegalArgumentException e) {
            rc.response()
                    .setStatusCode(400)
                    .putHeader("Content-Type", "text/plain")
                    .end("Invalid UUID format");
        }
    }
}