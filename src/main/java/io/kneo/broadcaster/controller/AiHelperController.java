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

        router.get("/api/ai/memory/:brand").handler(this::handleGetMemory);
        router.post("/api/ai/memory/:brand").handler(this::handleSaveMemory);
        router.delete("/api/ai/memory/:brand").handler(this::handleClearMemory);
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

    private void handleGetMemory(RoutingContext rc) {
        String brand = rc.pathParam("brand");
        memoryService.getMemory(brand)
                .subscribe().with(
                        memory -> rc.response()
                                .putHeader("Content-Type", "application/json")
                                .end(Json.encode(memory)),
                        failure -> rc.response()
                                .setStatusCode(404)
                                .end(failure.getMessage())
                );
    }

    private void handleSaveMemory(RoutingContext rc) {
        String brand = rc.pathParam("brand");
        ConversationMemory memory = rc.body().asJsonObject().mapTo(ConversationMemory.class);

        memoryService.saveMemory(brand, memory)
                .subscribe().with(
                        saved -> rc.response()
                                .putHeader("Content-Type", "application/json")
                                .end(Json.encode(saved)),
                        failure -> rc.response()
                                .setStatusCode(400)
                                .end(failure.getMessage())
                );
    }

    private void handleClearMemory(RoutingContext rc) {
        String brand = rc.pathParam("brand");
        memoryService.clearMemory(brand)
                .subscribe().with(
                        cleared -> rc.response()
                                .setStatusCode(204)
                                .end(),
                        failure -> rc.response()
                                .setStatusCode(400)
                                .end(failure.getMessage())
                );
    }
}