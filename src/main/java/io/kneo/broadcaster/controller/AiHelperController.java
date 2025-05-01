package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.model.ConversationMemory;
import io.kneo.broadcaster.service.AiHelperService;
import io.kneo.broadcaster.service.MemoryService;
import io.vertx.core.json.Json;
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

    private final AiHelperService service;
    private final MemoryService memoryService;

    @Inject
    public AiHelperController(AiHelperService service, MemoryService memoryService) {
        this.service = service;
        this.memoryService = memoryService;
    }

    public void setupRoutes(Router router) {
        router.route().handler(BodyHandler.create());
        router.get("/api/ai/brands/status").handler(this::handleGetBrandsByStatus);

        // Memory endpoints
        router.get("/api/ai/memory/brand/:brandId").handler(this::handleGetMemoriesByBrand);
        router.get("/api/ai/memory/:id").handler(this::handleGetMemory);
        router.post("/api/ai/memory").handler(this::handleCreateMemory);
        router.put("/api/ai/memory/:id").handler(this::handleUpdateMemory);
        router.delete("/api/ai/memory/:id").handler(this::handleDeleteMemory);
    }

    private void handleGetBrandsByStatus(RoutingContext rc) {
        List<String> statusParams = rc.queryParam("status");

        if (statusParams.isEmpty()) {
            rc.response()
                    .setStatusCode(400)
                    .end("At least one status is required. Valid values: " +
                            Arrays.toString(RadioStationStatus.values()));
            return;
        }

        try {
            List<RadioStationStatus> statuses = statusParams.stream()
                    .map(String::toUpperCase)
                    .map(RadioStationStatus::valueOf)
                    .collect(Collectors.toList());

            service.getBrandStatus(statuses)
                    .subscribe().with(
                            brands -> rc.response()
                                    .putHeader("Content-Type", "application/json")
                                    .end(Json.encode(brands)),
                            failure -> rc.response()
                                    .setStatusCode(400)
                                    .end(failure.getMessage())
                    );
        } catch (IllegalArgumentException e) {
            rc.response()
                    .setStatusCode(400)
                    .end("Invalid status. Valid values: " +
                            Arrays.toString(RadioStationStatus.values()));
        }
    }

    private void handleGetMemoriesByBrand(RoutingContext rc) {
        try {
            UUID brandId = UUID.fromString(rc.pathParam("brandId"));
            int limit = rc.queryParam("limit").isEmpty() ? 10 : Integer.parseInt(rc.queryParam("limit").get(0));
            int offset = rc.queryParam("offset").isEmpty() ? 0 : Integer.parseInt(rc.queryParam("offset").get(0));

            memoryService.getByBrandId(brandId, limit, offset)
                    .subscribe().with(
                            memories -> rc.response()
                                    .putHeader("Content-Type", "application/json")
                                    .end(Json.encode(memories)),
                            failure -> rc.response()
                                    .setStatusCode(404)
                                    .end(failure.getMessage())
                    );
        } catch (IllegalArgumentException e) {
            rc.response()
                    .setStatusCode(400)
                    .end("Invalid UUID format or pagination parameters");
        }
    }

    private void handleGetMemory(RoutingContext rc) {
        try {
            UUID id = UUID.fromString(rc.pathParam("id"));
            memoryService.getById(id)
                    .subscribe().with(
                            memory -> rc.response()
                                    .putHeader("Content-Type", "application/json")
                                    .end(Json.encode(memory)),
                            failure -> rc.response()
                                    .setStatusCode(404)
                                    .end(failure.getMessage())
                    );
        } catch (IllegalArgumentException e) {
            rc.response()
                    .setStatusCode(400)
                    .end("Invalid UUID format");
        }
    }

    private void handleCreateMemory(RoutingContext rc) {
        try {
            ConversationMemory memory = rc.body().asJsonObject().mapTo(ConversationMemory.class);
            memoryService.create(memory)
                    .subscribe().with(
                            created -> rc.response()
                                    .setStatusCode(201)
                                    .putHeader("Content-Type", "application/json")
                                    .end(Json.encode(created)),
                            failure -> rc.response()
                                    .setStatusCode(400)
                                    .end(failure.getMessage())
                    );
        } catch (Exception e) {
            rc.response()
                    .setStatusCode(400)
                    .end("Invalid request body");
        }
    }

    private void handleUpdateMemory(RoutingContext rc) {
        try {
            UUID id = UUID.fromString(rc.pathParam("id"));
            ConversationMemory memory = rc.body().asJsonObject().mapTo(ConversationMemory.class);
            memoryService.update(id, memory)
                    .subscribe().with(
                            updated -> rc.response()
                                    .putHeader("Content-Type", "application/json")
                                    .end(Json.encode(updated)),
                            failure -> rc.response()
                                    .setStatusCode(400)
                                    .end(failure.getMessage())
                    );
        } catch (IllegalArgumentException e) {
            rc.response()
                    .setStatusCode(400)
                    .end("Invalid UUID format or request body");
        }
    }

    private void handleDeleteMemory(RoutingContext rc) {
        try {
            UUID id = UUID.fromString(rc.pathParam("id"));
            memoryService.delete(id)
                    .subscribe().with(
                            deleted -> rc.response()
                                    .setStatusCode(204)
                                    .end(),
                            failure -> rc.response()
                                    .setStatusCode(400)
                                    .end(failure.getMessage())
                    );
        } catch (IllegalArgumentException e) {
            rc.response()
                    .setStatusCode(400)
                    .end("Invalid UUID format");
        }
    }
}