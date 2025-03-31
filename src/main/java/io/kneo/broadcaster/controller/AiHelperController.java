package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.service.AiHelperService;
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

    @Inject
    public AiHelperController(AiHelperService service) {
        this.service = service;
    }

    public void setupRoutes(Router router) {
        router.route().handler(BodyHandler.create());
        router.get("/api/ai/brands/status").handler(this::handleGetBrandsByStatus);
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
}