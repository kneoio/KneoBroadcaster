package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
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
        router.get("/api/ai/memory/:brand/:type").handler(this::handleGetMemoriesByType);
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
                                    .setStatusCode(400)
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

    private void handleGetMemoriesByType(RoutingContext rc) {
        try {
            String brand = rc.pathParam("brand");
            String type = rc.pathParam("type");

            memoryService.getByType(brand, type)
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

}