package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.dto.queue.AddToQueueDTO;
import io.kneo.broadcaster.service.QueueService;
import io.kneo.broadcaster.service.RadioService;
import io.kneo.broadcaster.util.ProblemDetailsUtil;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

@ApplicationScoped
public class QueueController {
    private static final Logger LOGGER = LoggerFactory.getLogger(QueueController.class);

    private final RadioService radioService;
    private final QueueService queueService;

    @Inject
    public QueueController(RadioService radioService, QueueService queueService) {
        this.radioService = radioService;
        this.queueService = queueService;
    }

    public void setupRoutes(Router router) {
        String path = "/api/:brand/queue";
        router.route(HttpMethod.PUT, path + "/action").handler(this::action);
        router.route(HttpMethod.POST, path + "/add").handler(this::addToQueue);
    }

    private void addToQueue(RoutingContext rc) {
        String brand = rc.pathParam("brand").toLowerCase();

        JsonObject body = rc.body().asJsonObject();
        if (body == null) {
            ProblemDetailsUtil.respondValidationError(
                    rc,
                    "Invalid or missing JSON body",
                    Map.of("body", List.of("Body is required"))
            );
            return;
        }

        AddToQueueDTO dto;
        try {
            dto = body.mapTo(AddToQueueDTO.class);
        } catch (Exception e) {
            ProblemDetailsUtil.respondValidationError(
                    rc,
                    "Failed to parse request body",
                    Map.of("body", List.of(e.getMessage() != null ? e.getMessage() : "Invalid payload"))
            );
            return;
        }

        if (dto.getMergingMethod() == null) {
            ProblemDetailsUtil.respondValidationError(
                    rc,
                    "Validation failed",
                    Map.of("mergingMethod", List.of("Field 'mergingMethod' is required"))
            );
            return;
        }

        queueService.addToQueue(brand, dto, null)
                .subscribe().with(
                        ok -> {
                            LOGGER.info("Queue add completed for brand {}", brand);
                            JsonObject resp = new JsonObject();
                            resp.put("status", "ok");
                            rc.response()
                                    .setStatusCode(200)
                                    .putHeader("Content-Type", MediaType.APPLICATION_JSON)
                                    .end(resp.encode());
                        },
                        err -> {
                            LOGGER.error("Queue add failed for brand {}: {}", brand, err.getMessage());
                            rc.response()
                                    .setStatusCode(500)
                                    .putHeader("Content-Type", MediaType.TEXT_PLAIN)
                                    .end("Failed to enqueue request: " + err.getMessage());
                        }
                );
    }



    private void action(RoutingContext rc) {
        String brand = rc.pathParam("brand").toLowerCase();
        JsonObject jsonObject = rc.body().asJsonObject();
        String action = jsonObject.getString("action");
        if ("start".equalsIgnoreCase(action)) {
            LOGGER.info("Starting radio station for brand: {}", brand);
            radioService.initializeStation(brand)
                    .subscribe().with(
                            station -> {
                                rc.response()
                                        .putHeader("Content-Type", MediaType.APPLICATION_JSON)
                                        .setStatusCode(200)
                                        .end("{\"status\":\"" + station.getStatus() + "\"}");
                            },
                            throwable -> {
                                LOGGER.error("Error starting radio station: {}", throwable.getMessage());
                                rc.response()
                                        .setStatusCode(500)
                                        .putHeader("Content-Type", MediaType.TEXT_PLAIN)
                                        .end("Failed to start radio station: " + throwable.getMessage());
                            }
                    );
        } else if ("stop".equalsIgnoreCase(action)) {
            LOGGER.info("Stopping radio station for brand: {}", brand);
            radioService.stopStation(brand)
                    .subscribe().with(
                            station -> {
                                rc.response()
                                        .putHeader("Content-Type", MediaType.APPLICATION_JSON)
                                        .setStatusCode(200)
                                        .end("{\"status\":\"OK\"}");
                            },
                            throwable -> {
                                LOGGER.error("Error stopping radio station: {}", throwable.getMessage());
                                rc.response()
                                        .setStatusCode(500)
                                        .putHeader("Content-Type", MediaType.TEXT_PLAIN)
                                        .end("Failed to stop radio station: " + throwable.getMessage());
                            }
                    );
        } else {
            rc.response()
                    .setStatusCode(400)
                    .putHeader("Content-Type", MediaType.TEXT_PLAIN)
                    .end("Invalid action. Supported actions: 'start', 'stop'");
        }
    }
}