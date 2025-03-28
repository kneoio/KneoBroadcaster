package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.dto.QueueItemDTO;
import io.kneo.broadcaster.model.InterstitialPlaylistItem;
import io.kneo.broadcaster.service.QueueService;
import io.kneo.broadcaster.service.RadioService;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.FileUpload;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

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
        router.route(HttpMethod.GET, path + "/current").handler(this::getCurrentSong);
        router.route(HttpMethod.PUT, path + "/action").handler(this::action);
        router.route(HttpMethod.POST, path + "/:songId").handler(this::add);
    }

    private void get(RoutingContext ctx) {
        String brand = ctx.pathParam("brand");

        service.getQueueForBrand(brand)
                .subscribe().with(
                        items -> {
                            ctx.response()
                                    .putHeader("Content-Type", "application/json")
                                    .end(Json.encode(items));
                        },
                        error -> ctx.fail(500, error)
                );
    }

    private void getCurrentSong(RoutingContext rc) {
        String brand = rc.pathParam("brand");

        service.getCurrentlyPlayingSong(brand)
                .subscribe().with(
                        currentSong -> {
                            JsonObject response = new JsonObject();
                            if (currentSong != null) {
                                response.put("currentSong", currentSong);
                                response.put("success", true);
                            } else {
                                response.put("success", false);
                                response.put("message", "No song currently playing");
                            }

                            rc.response()
                                    .putHeader("Content-Type", "application/json")
                                    .end(Json.encode(response));
                        },
                        error -> {
                            LOGGER.error("Error getting current song: {}", error.getMessage());
                            rc.fail(500, error);
                        }
                );
    }

    private void add(RoutingContext rc) {
        String brand = rc.pathParam("brand");
        String songIdAsString = rc.pathParam("songId");

        // Handle file upload
        if (rc.request().isExpectMultipart()) {
            List<FileUpload> files = rc.fileUploads();

            if (files.isEmpty()) {
                rc.response()
                        .setStatusCode(400)
                        .putHeader("Content-Type", "application/json")
                        .end(Json.encode(new JsonObject().put("success", false).put("message", "No file uploaded")));
                return;
            }

            FileUpload file = files.get(0);
            if (!file.fileName().toLowerCase().endsWith(".mp3")) {
                rc.response()
                        .setStatusCode(400)
                        .putHeader("Content-Type", "application/json")
                        .end(Json.encode(new JsonObject().put("success", false).put("message", "Only MP3 files are supported")));
                return;
            }

            String fileName = UUID.randomUUID() + "_" + file.fileName();
            String uploadDir = "uploads/" + brand;
            String filePath = uploadDir + "/" + fileName;

            try {
                File dir = new File(uploadDir);
                if (!dir.exists()) dir.mkdirs();

                Files.copy(Paths.get(file.uploadedFileName()), Paths.get(filePath));

                service.addToQueue(brand, UUID.fromString(songIdAsString), filePath)
                        .subscribe().with(
                                success -> {
                                    rc.response()
                                            .setStatusCode(201)
                                            .putHeader("Content-Type", "application/json")
                                            .end(Json.encode(new JsonObject()
                                                    .put("success", true)
                                                    .put("fileName", file.fileName())));
                                },
                                error -> {
                                    rc.response()
                                            .setStatusCode(500)
                                            .putHeader("Content-Type", "application/json")
                                            .end(Json.encode(new JsonObject()
                                                    .put("success", false)
                                                    .put("message", "Error adding file to queue")));
                                }
                        );
            } catch (IOException e) {
                rc.response()
                        .setStatusCode(500)
                        .putHeader("Content-Type", "application/json")
                        .end(Json.encode(new JsonObject().put("success", false).put("message", "Error saving file")));
            }
            return;
        }

        // Handle UUID from path
        try {
            UUID songId = UUID.fromString(rc.pathParam("songId"));

            service.addToQueue(brand, songId)
                    .subscribe().with(
                            success -> {
                                rc.response()
                                        .setStatusCode(success ? 201 : 400)
                                        .putHeader("Content-Type", "application/json")
                                        .end(Json.encode(new JsonObject()
                                                .put("success", success)
                                                .put("message", success ? null : "Failed to add song to queue")));
                            },
                            error -> rc.fail(500, error)
                    );
        } catch (IllegalArgumentException e) {
            rc.response()
                    .setStatusCode(400)
                    .putHeader("Content-Type", "application/json")
                    .end(Json.encode(new JsonObject().put("success", false).put("message", "Invalid song ID")));
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
                                            .putHeader("Access-Control-Allow-Origin", "*")
                                            .setStatusCode(200)
                                            .end("{\"status\":\"" + station.getStatus() + "}");
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

    @Deprecated
    private QueueItemDTO convertToDTO(InterstitialPlaylistItem item) {
        QueueItemDTO dto = new QueueItemDTO();
        dto.setId(item.getId());
        dto.setMetadata(item.getMetadata());
        dto.setType(item.getType());
        dto.setPriority(item.getPriority());
        return dto;
    }
}