package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.dto.cnst.SSEProgressStatus;
import io.kneo.broadcaster.dto.queue.AddToQueueDTO;
import io.kneo.broadcaster.dto.queue.SSEProgressDTO;
import io.kneo.broadcaster.service.QueueService;
import io.kneo.broadcaster.service.RadioService;
import io.kneo.broadcaster.util.ProblemDetailsUtil;
import io.vertx.core.Vertx;
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
    private final Vertx vertx;

    @Inject
    public QueueController(RadioService radioService, QueueService queueService, Vertx vertx) {
        this.radioService = radioService;
        this.queueService = queueService;
        this.vertx = vertx;
    }

    public void setupRoutes(Router router) {
        String path = "/api/:brand/queue";
        router.route(HttpMethod.PUT, path + "/action").handler(this::action);
        router.route(HttpMethod.POST, path + "/add").handler(this::addToQueue);
        router.route(HttpMethod.GET, path + "/progress/:processId/stream").handler(this::streamProgress);
    }

    private void addToQueue(RoutingContext rc) {
        String brand = rc.pathParam("brand").toLowerCase();
        String processId = rc.request().getParam("processId");

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

        if (processId != null) {
            queueService.initializeProgress(processId, "Queue request");
        }

        try {
            queueService.addToQueue(brand, dto, processId)
                    .subscribe().with(
                            ok -> {
                                LOGGER.info("Queue add completed for brand {}, processId: {}", brand, processId);
                            },
                            err -> {
                                LOGGER.error("Queue add failed for brand {}: {}", brand, err.getMessage());
                            }
                    );
        } catch (Exception e) {
            if (processId != null) {
                SSEProgressDTO errorDto = new SSEProgressDTO();
                errorDto.setId(processId);
                errorDto.setErrorMessage(e.getMessage());
                errorDto.setStatus(SSEProgressStatus.ERROR);
                queueService.queuingProgressMap.put(processId, errorDto);
            }
            rc.response()
                    .setStatusCode(500)
                    .putHeader("Content-Type", MediaType.TEXT_PLAIN)
                    .end("Failed to enqueue request: " + e.getMessage());
            return;
        }

        JsonObject resp = new JsonObject();
        resp.put("status", "accepted");
        if (processId != null) {
            resp.put("processId", processId);
        }
        rc.response()
                .setStatusCode(202)
                .putHeader("Content-Type", MediaType.APPLICATION_JSON)
                .end(resp.encode());
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
                                        .end("{\"status\":\"" + station.getStatus() + "}");
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

    private void streamProgress(RoutingContext rc) {
        String processId = rc.pathParam("processId");

        rc.response()
                .putHeader("Content-Type", "text/event-stream")
                .putHeader("Cache-Control", "no-cache")
                .putHeader("Connection", "keep-alive")
                .setChunked(true);

        long timerId = vertx.setPeriodic(500, id -> {
            SSEProgressDTO progress = queueService.getQueuingProgress(processId);
            if (progress != null) {
                rc.response().write("data: " + JsonObject.mapFrom(progress).encode() + "\n\n");

                if (progress.getStatus() == SSEProgressStatus.DONE ||
                    progress.getStatus() == SSEProgressStatus.ERROR) {
                    vertx.cancelTimer(id);
                    rc.response().end();
                    vertx.setTimer(5000, tid -> queueService.queuingProgressMap.remove(processId));
                }
            }
        });

        rc.request().connection().closeHandler(v -> {
            vertx.cancelTimer(timerId);
        });
    }
}