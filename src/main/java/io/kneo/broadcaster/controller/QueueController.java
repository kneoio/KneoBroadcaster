package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.dto.QueueItemDTO;
import io.kneo.broadcaster.model.InterstitialPlaylistItem;
import io.kneo.broadcaster.service.QueueService;
import io.kneo.broadcaster.service.RadioService;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class QueueController {
    private static final Logger LOGGER = LoggerFactory.getLogger(QueueController.class);

    private final QueueService service;

    private final RadioService radioService;

    @Inject
    public QueueController(QueueService service,  RadioService radioService) {
        this.service = service;
        this.radioService = radioService;
    }

    public void setupRoutes(Router router) {
        String path = "/:brand/queue";
        router.route(HttpMethod.GET, path).handler(this::get);
        router.route(HttpMethod.PUT, path + "/action").handler(this::action);
        router.route(HttpMethod.POST, path).handler(this::add);
    }

    private void get(RoutingContext ctx) {
        String brand = ctx.pathParam("brand");

        service.getQueuedItems(brand)
                .subscribe().with(
                        items -> {
                            List<QueueItemDTO> dtos = items.stream()
                                    .map(this::convertToDTO)
                                    .collect(Collectors.toList());

                            ctx.response()
                                    .putHeader("Content-Type", "application/json")
                                    .end(Json.encode(dtos));
                        },
                        error -> ctx.fail(500, error)
                );
    }

    private void add(RoutingContext rc) {
        String brand = rc.pathParam("brand");

        try {
            JsonObject jsonObject = rc.body().asJsonObject();
            QueueItemDTO dto = jsonObject.mapTo(QueueItemDTO.class);
            String filePath = jsonObject.getString("filePath", "/audio/default.mp3");

            InterstitialPlaylistItem item = new InterstitialPlaylistItem(
                    Path.of(filePath),
                    dto.getMetadata(),
                    dto.getType(),
                    dto.getPriority() > 0 ? dto.getPriority() : 100
            );

            service.addItem(brand, item)
                    .subscribe().with(
                            success -> {
                                QueueItemDTO responseDto = convertToDTO(item);
                                responseDto.setId(item.getId());

                                rc.response()
                                        .setStatusCode(201)
                                        .putHeader("Content-Type", "application/json")
                                        .end(Json.encode(responseDto));
                            },
                            error -> rc.fail(500, error)
                    );
        } catch (Exception e) {
            LOGGER.error("Error adding to queue: {}", e.getMessage());
            rc.fail(400, e);
        }
    }

    private void action(RoutingContext rc) {
        String brand = rc.pathParam("brand");
        JsonObject jsonObject = rc.body().asJsonObject();

        String action = jsonObject.getString("action");

        if ("start".equalsIgnoreCase(action)) {
            LOGGER.info("Starting radio station for brand: {}", brand);
            radioService.initializeStation(brand)
                    .subscribe().with(
                            station -> {
                                rc.response()
                                        .putHeader("Content-Type", MediaType.APPLICATION_JSON)
                                        .putHeader("Access-Control-Allow-Origin", "*")
                                        .setStatusCode(200)
                                        .end("{\"status\":\"" + station.getStatus() + "\", \"segments\":" +
                                                station.getPlaylist().getSegmentCount() + "}");
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
                                            .putHeader("Access-Control-Allow-Origin", "*")
                                            .setStatusCode(200)
                                            .end("{\"status\":\"" + station.getStatus() + "\", \"segments\":" +
                                                    station.getPlaylist().getSegmentCount() + "}");
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
                    .end("Invalid action. Supported actions: 'start'");
        }
    }

    private QueueItemDTO convertToDTO(InterstitialPlaylistItem item) {
        QueueItemDTO dto = new QueueItemDTO();
        dto.setId(item.getId());
        dto.setMetadata(item.getMetadata());
        dto.setType(item.getType());
        dto.setPriority(item.getPriority());
        return dto;
    }
}