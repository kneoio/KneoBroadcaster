package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.dto.aihelper.SongIntroductionDTO;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class AiHelperController {
    private static final Logger LOGGER = LoggerFactory.getLogger(AiHelperController.class);

    private final AiHelperService aiHelperService;
    private final MemoryService memoryService;

    @Inject
    public AiHelperController(AiHelperService aiHelperService, MemoryService memoryService) {
        this.aiHelperService = aiHelperService;
        this.memoryService = memoryService;
    }

    public void setupRoutes(Router router) {
        router.route("/api/ai/*").handler(BodyHandler.create());
        router.get("/api/ai/brands/status").handler(this::getBrandsByStatus);
        router.get("/api/ai/memory/:brand/:type").handler(this::getMemoriesByType);
        router.patch("/api/ai/memory/history/brand/:brand").handler(this::patch);
    }

    private void getBrandsByStatus(RoutingContext rc) {
        parseStatusParameters(rc)
                .chain(aiHelperService::getByStatus)
                .subscribe().with(
                        brands -> rc.response()
                                .putHeader("Content-Type", "application/json")
                                .end(Json.encode(brands)),
                        throwable -> {
                            LOGGER.error("Error getting brands by status", throwable);
                            if (throwable instanceof IllegalArgumentException) {
                                rc.response()
                                        .setStatusCode(400)
                                        .putHeader("Content-Type", "text/plain")
                                        .end("Invalid status value provided. Valid values: " +
                                                Arrays.toString(RadioStationStatus.values()));
                            } else {
                                rc.response()
                                        .setStatusCode(500)
                                        .putHeader("Content-Type", "text/plain")
                                        .end("An unexpected error occurred retrieving brands.");
                            }
                        }
                );
    }

    private Uni<List<RadioStationStatus>> parseStatusParameters(RoutingContext rc) {
        return Uni.createFrom().item(() -> {
            List<String> statusParams = rc.queryParam("status");

            if (statusParams == null || statusParams.isEmpty()) {
                throw new IllegalArgumentException("At least one status query parameter is required. Valid values: " +
                        Arrays.toString(RadioStationStatus.values()));
            }

            return statusParams.stream()
                    .map(String::toUpperCase)
                    .map(RadioStationStatus::valueOf)
                    .collect(Collectors.toList());
        });
    }

    private void getMemoriesByType(RoutingContext rc) {
        parseMemoryParameters(rc)
                .chain(params -> memoryService.getByType(params.brand, params.type, SuperUser.build()))
                .subscribe().with(
                        memories -> rc.response()
                                .putHeader("Content-Type", "application/json")
                                .end(Json.encode(memories)),
                        throwable -> {
                            LOGGER.error("Error getting memories by type", throwable);
                            if (throwable instanceof IllegalArgumentException) {
                                rc.response()
                                        .setStatusCode(400)
                                        .putHeader("Content-Type", "text/plain")
                                        .end(throwable.getMessage());
                            } else {
                                rc.response()
                                        .setStatusCode(500)
                                        .putHeader("Content-Type", "text/plain")
                                        .end("An unexpected error occurred retrieving memories.");
                            }
                        }
                );
    }

    private Uni<MemoryParams> parseMemoryParameters(RoutingContext rc) {
        return Uni.createFrom().item(() -> {
            String brand = rc.pathParam("brand");
            String type = rc.pathParam("type");

            if (brand == null || brand.trim().isEmpty()) {
                throw new IllegalArgumentException("Brand parameter is required");
            }
            if (type == null || type.trim().isEmpty()) {
                throw new IllegalArgumentException("Type parameter is required");
            }

            return new MemoryParams(brand, type);
        });
    }

    private void patch(RoutingContext rc) {
        parsePatchParameters(rc)
                .chain(params -> memoryService.patch(params.brand, params.dto, SuperUser.build()))
                .subscribe().with(
                        doc -> rc.response().setStatusCode(200).end(),
                        throwable -> {
                            LOGGER.error("Error patching memory", throwable);
                            if (throwable instanceof IllegalArgumentException) {
                                rc.response()
                                        .setStatusCode(400)
                                        .putHeader("Content-Type", "text/plain")
                                        .end(throwable.getMessage());
                            } else {
                                rc.response()
                                        .setStatusCode(500)
                                        .putHeader("Content-Type", "text/plain")
                                        .end("An unexpected error occurred updating memory.");
                            }
                        }
                );
    }

    private Uni<PatchParams> parsePatchParameters(RoutingContext rc) {
        return Uni.createFrom().item(() -> {
            String brand = rc.pathParam("brand");
            if (brand == null || brand.trim().isEmpty()) {
                throw new IllegalArgumentException("Brand parameter is required");
            }

            JsonObject jsonObject = rc.body().asJsonObject();
            if (jsonObject == null) {
                throw new IllegalArgumentException("Request body must be a valid JSON object");
            }

            SongIntroductionDTO dto = jsonObject.mapTo(SongIntroductionDTO.class);
            return new PatchParams(brand, dto);
        });
    }

    private record MemoryParams(String brand, String type) {
    }

    private record PatchParams(String brand, SongIntroductionDTO dto) {
    }
}