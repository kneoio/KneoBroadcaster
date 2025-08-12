package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.service.QueueService;
import io.kneo.broadcaster.service.RadioService;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
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

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class QueueController {
    private static final Logger LOGGER = LoggerFactory.getLogger(QueueController.class);

    private final QueueService service;
    private final RadioService radioService;
    private final BroadcasterConfig config;
    private final Vertx vertx;

    @Inject
    public QueueController(QueueService service, RadioService radioService, BroadcasterConfig config, Vertx vertx) {
        this.service = service;
        this.radioService = radioService;
        this.config = config;
        this.vertx = vertx;
    }

    public void setupRoutes(Router router) {
        String path = "/api/:brand/queue";
        router.route(HttpMethod.GET, path).handler(this::get);
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

    private void add(RoutingContext rc) {
        String brand = rc.pathParam("brand");
        String songIdAsString = rc.pathParam("songId");

        processAllFiles(rc, brand)
                .onItem().transformToUni(filePaths ->
                        service.addToQueue(brand, UUID.fromString(songIdAsString), filePaths)
                                .onItem().transform(ignored -> filePaths)
                )
                .subscribe().with(
                        filePaths -> {
                            rc.response()
                                    .setStatusCode(filePaths.isEmpty() ? 200 : 201)
                                    .putHeader("Content-Type", "application/json")
                                    .end(Json.encode(new JsonObject()
                                            .put("success", true)
                                            .put("filesUploaded", filePaths.size())
                                            .put("filePaths", filePaths)));
                        },
                        error -> rc.fail(500, error)
                );
    }

    private Uni<List<String>> processAllFiles(RoutingContext rc, String brand) {
        List<FileUpload> files = rc.fileUploads();
        if (files.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }

        String uploadDir = config.getPathUploads() + "/" + brand;
        var fs = vertx.fileSystem();

        return Uni.createFrom().completionStage(fs.mkdirs(uploadDir).toCompletionStage())
                .onItem().transformToUni(v -> {
                    Map<String, FileUpload> uniqueFiles = deduplicateFiles(files);

                    List<Uni<String>> fileProcessingTasks = uniqueFiles.values().stream()
                            .map(file -> processFile(file, uploadDir, fs))
                            .toList();

                    return Uni.combine().all().unis(fileProcessingTasks)
                            .with(results -> results.stream()
                                    .map(String.class::cast)
                                    .filter(Objects::nonNull)
                                    .collect(Collectors.toList()));
                });
    }

    private Map<String, FileUpload> deduplicateFiles(List<FileUpload> files) {
        return files.stream()
                .collect(Collectors.toMap(
                        file -> file.fileName() + ":" + file.size(),
                        file -> file,
                        (existing, duplicate) -> existing
                ));
    }

    private Uni<String> processFile(FileUpload file, String uploadDir, io.vertx.core.file.FileSystem fs) {
        String originalFileName = file.fileName();
        String sanitizedFileName = sanitizeFileName(originalFileName);
        String uploadedPath = file.uploadedFileName();
        String newFilePath = uploadDir + "/" + sanitizedFileName;

        LOGGER.info("FILE_PROCESS - Processing: '{}' -> '{}' (uploaded from: '{}')",
                originalFileName, newFilePath, uploadedPath);

        return Uni.createFrom().completionStage(
                fs.exists(uploadedPath).toCompletionStage()
        ).onItem().transformToUni(exists -> {
            if (!exists) {
                LOGGER.error("FILE_NOT_FOUND - Source file missing: {}", uploadedPath);
                return Uni.createFrom().failure(new IllegalStateException("Source file not found: " + uploadedPath));
            }

            return checkAndResolveDestination(newFilePath, fs)
                    .onItem().transformToUni(finalPath -> {
                        LOGGER.debug("FILE_MOVE - Moving file from '{}' to '{}'", uploadedPath, finalPath);

                        return Uni.createFrom().completionStage(
                                        fs.move(uploadedPath, finalPath).toCompletionStage()
                                ).onItem().invoke(v -> LOGGER.info("FILE_MOVED - Success: {}", finalPath))
                                .onFailure().invoke(error -> LOGGER.error("FILE_MOVE_FAILED - Error moving '{}' to '{}': {}",
                                        uploadedPath, finalPath, error.getMessage()))
                                .onItem().transform(v -> finalPath);
                    });
        });
    }

    private Uni<String> checkAndResolveDestination(String originalPath, io.vertx.core.file.FileSystem fs) {
        return Uni.createFrom().completionStage(fs.exists(originalPath).toCompletionStage())
                .onItem().transform(exists -> {
                    if (!exists) {
                        return originalPath;
                    }

                    String directory = originalPath.substring(0, originalPath.lastIndexOf('/'));
                    String filename = originalPath.substring(originalPath.lastIndexOf('/') + 1);
                    String name = filename.contains(".") ?
                            filename.substring(0, filename.lastIndexOf('.')) : filename;
                    String extension = filename.contains(".") ?
                            filename.substring(filename.lastIndexOf('.')) : "";

                    return directory + "/" + name + "_" + System.currentTimeMillis() + extension;
                });
    }

    private String sanitizeFileName(String fileName) {
        return fileName.replaceAll("[/\\\\:*?\"<>|]", "_");
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
}