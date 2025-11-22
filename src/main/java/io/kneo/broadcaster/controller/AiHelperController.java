package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.dto.aihelper.llmtool.AvailableStationsAiDTO;
import io.kneo.broadcaster.dto.aihelper.llmtool.ListenerAiDTO;
import io.kneo.broadcaster.dto.aihelper.llmtool.LiveRadioStationStatAiDTO;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.model.cnst.MemoryType;
import io.kneo.broadcaster.service.MemoryService;
import io.kneo.broadcaster.service.live.AiHelperService;
import io.kneo.core.localization.LanguageCode;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class AiHelperController {
    private static final Logger LOGGER = LoggerFactory.getLogger(AiHelperController.class);

    private final MemoryService memoryService;
    private final AiHelperService aiHelperService;

    public AiHelperController(MemoryService memoryService, AiHelperService aiHelperService) {
        this.memoryService = memoryService;
        this.aiHelperService = aiHelperService;
    }

    public void setupRoutes(Router router) {
        BodyHandler bodyHandler = BodyHandler.create();
        router.get("/api/ai/memory/:brand").handler(this::getMemoriesByType);
        router.get("/api/ai/messages/:brand/consume").handler(this::consumeInstantMessages);
        router.patch("/api/ai/memory/reset/:brand/:type").handler(bodyHandler).handler(this::resetMemory);
        router.get("/api/ai/live/stations").handler(this::getLiveRadioStations);
        router.get("/api/ai/station/:slug/live").handler(this::getStationLiveStat);
        router.get("/api/ai/listener/by-telegram-name/:name").handler(this::getListenerByTelegramName);
        router.get("/api/ai/stations").handler(this::getAllStations);
    }

    private void getMemoriesByType(RoutingContext rc) {
        String brand = rc.pathParam("brand");
        if (brand == null || brand.trim().isEmpty()) {
            rc.response()
                    .setStatusCode(400)
                    .putHeader("Content-Type", "text/plain")
                    .end("Brand parameter is required");
            return;
        }

        List<String> typeParams = rc.queryParam("type");
        if (typeParams == null || typeParams.isEmpty()) {
            rc.response()
                    .setStatusCode(400)
                    .putHeader("Content-Type", "text/plain")
                    .end("At least one type query parameter is required");
            return;
        }

        String[] types = typeParams.toArray(new String[0]);

        memoryService.getByType(brand, types)
                .subscribe().with(
                        content -> rc.response()
                                .putHeader("Content-Type", "application/json")
                                .end(Json.encode(content)),
                        throwable -> {
                            LOGGER.error("Error getting memories by brand", throwable);
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

    private void consumeInstantMessages(RoutingContext rc) {
        String brand = rc.pathParam("brand");
        if (brand == null || brand.trim().isEmpty()) {
            rc.response()
                    .setStatusCode(400)
                    .putHeader("Content-Type", "text/plain")
                    .end("Brand parameter is required");
            return;
        }

        memoryService.retrieveAndRemoveInstantMessages(brand)
                .subscribe().with(
                        messages -> rc.response()
                                .putHeader("Content-Type", "application/json")
                                .end(Json.encode(messages)),
                        throwable -> {
                            LOGGER.error("Error consuming instant messages for brand: {}", brand, throwable);
                            rc.response()
                                    .setStatusCode(500)
                                    .putHeader("Content-Type", "text/plain")
                                    .end("An error occurred while retrieving instant messages");
                        }
                );
    }

    private void resetMemory(RoutingContext rc) {
        String brand = rc.pathParam("brand");
        String type = rc.pathParam("type");

        if (brand == null || brand.trim().isEmpty()) {
            rc.response()
                    .setStatusCode(400)
                    .putHeader("Content-Type", "text/plain")
                    .end("Brand parameter is required");
            return;
        }

        if (type == null || type.trim().isEmpty()) {
            rc.response()
                    .setStatusCode(400)
                    .putHeader("Content-Type", "text/plain")
                    .end("Type parameter is required");
            return;
        }

        List<String> idParams = rc.queryParam("id");
        String memoryId;
        if (idParams != null && !idParams.isEmpty()) {
            memoryId = idParams.get(0);
        } else {
            memoryId = null;
        }

        try {
            MemoryType memoryType = MemoryType.valueOf(type.toUpperCase());

            if (memoryId != null && !memoryId.trim().isEmpty()) {
                memoryService.delete(memoryId.trim())
                        .subscribe().with(
                                removedCount -> rc.response()
                                        .putHeader("Content-Type", "application/json")
                                        .end(Json.encode(new JsonObject().put("removedCount", removedCount))),
                                throwable -> {
                                    LOGGER.error("Error deleting memory by id: {} for brand: {}", memoryId, brand, throwable);
                                    rc.response()
                                            .setStatusCode(500)
                                            .putHeader("Content-Type", "text/plain")
                                            .end("An error occurred while deleting memory by id");
                                }
                        );
            } else {
                memoryService.resetMemory(brand, memoryType)
                        .subscribe().with(
                                removedCount -> rc.response()
                                        .putHeader("Content-Type", "application/json")
                                        .end(Json.encode(new JsonObject().put("removedCount", removedCount))),
                                throwable -> {
                                    LOGGER.error("Error resetting memory for brand: {} and type: {}", brand, type, throwable);
                                    rc.response()
                                            .setStatusCode(500)
                                            .putHeader("Content-Type", "text/plain")
                                            .end("An error occurred while resetting memory");
                                }
                        );
            }
        } catch (IllegalArgumentException e) {
            rc.response()
                    .setStatusCode(400)
                    .putHeader("Content-Type", "text/plain")
                    .end("Invalid memory type. Valid values: " + Arrays.toString(MemoryType.values()));
        }
    }

    private void getLiveRadioStations(RoutingContext rc) {
        String statusesParam = rc.queryParam("statuses").isEmpty() ? null : rc.queryParam("statuses").get(0);
        if (statusesParam == null || statusesParam.trim().isEmpty()) {
            rc.response()
                    .setStatusCode(400)
                    .putHeader("Content-Type", "text/plain")
                    .end("Query parameter 'statuses' is required. Valid values: " + Arrays.toString(RadioStationStatus.values()));
            return;
        }

        try {
            List<RadioStationStatus> statuses = Arrays.stream(statusesParam.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(RadioStationStatus::valueOf)
                    .collect(Collectors.toList());

            aiHelperService.getOnline(statuses)
                    .subscribe().with(
                            liveContainer -> rc.response()
                                    .setStatusCode(200)
                                    .putHeader("Content-Type", "application/json")
                                    .end(JsonObject.mapFrom(liveContainer).encode()),
                            throwable -> {
                                LOGGER.error("Error getting live radio stations", throwable);
                                rc.response()
                                        .setStatusCode(500)
                                        .putHeader("Content-Type", "text/plain")
                                        .end("An error occurred while retrieving live radio stations");
                            }
                    );
        } catch (IllegalArgumentException e) {
            rc.response()
                    .setStatusCode(400)
                    .putHeader("Content-Type", "text/plain")
                    .end("Invalid statuses. Valid values: " + Arrays.toString(RadioStationStatus.values()));
        }
    }

    private void getListenerByTelegramName(RoutingContext rc) {
        String name = rc.pathParam("name");
        if (name == null || name.trim().isEmpty()) {
            rc.response()
                    .setStatusCode(400)
                    .putHeader("Content-Type", "text/plain")
                    .end("Telegram name parameter is required");
            return;
        }

        aiHelperService.getListenerByTelegramName(name.trim())
                .subscribe().with((ListenerAiDTO dto) -> {
                    if (dto == null) {
                        rc.response().setStatusCode(404).end();
                        return;
                    }
                    rc.response()
                            .setStatusCode(200)
                            .putHeader("Content-Type", "application/json")
                            .end(Json.encode(dto));
                }, throwable -> {
                    LOGGER.error("Error getting listener by telegram name", throwable);
                    rc.response()
                            .setStatusCode(500)
                            .putHeader("Content-Type", "text/plain")
                            .end("An error occurred while retrieving listener by telegram name");
                });
    }

    private void getStationLiveStat(RoutingContext rc) {
        String slug = rc.pathParam("slug");
        if (slug == null || slug.trim().isEmpty()) {
            rc.response()
                    .setStatusCode(400)
                    .putHeader("Content-Type", "text/plain")
                    .end("Slug parameter is required");
            return;
        }

        aiHelperService.getStationLiveStat(slug.trim())
                .subscribe().with(
                        (LiveRadioStationStatAiDTO dto) -> rc.response()
                                .setStatusCode(200)
                                .putHeader("Content-Type", "application/json")
                                .end(Json.encode(dto)),
                        throwable -> {
                            LOGGER.error("Error getting live station stat for slug {}", slug, throwable);
                            rc.response()
                                    .setStatusCode(500)
                                    .putHeader("Content-Type", "text/plain")
                                    .end("An error occurred while retrieving station live stat");
                        }
                );
    }

    private void getAllStations(RoutingContext rc) {
        try {
            String statusesParam = rc.queryParam("statuses").isEmpty() ? null : rc.queryParam("statuses").get(0);
            List<RadioStationStatus> statuses = null;
            if (statusesParam != null && !statusesParam.trim().isEmpty()) {
                statuses = Arrays.stream(statusesParam.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(RadioStationStatus::valueOf)
                        .collect(Collectors.toList());
            }

            String country = rc.queryParam("country").isEmpty() ? null : rc.queryParam("country").get(0);
            if (country != null) {
                country = country.trim();
                if (country.isEmpty()) country = null;
            }

            String djLangParam = rc.queryParam("djLanguage").isEmpty() ? null : rc.queryParam("djLanguage").get(0);
            if (djLangParam == null) {
                djLangParam = rc.queryParam("djlanguage").isEmpty() ? null : rc.queryParam("djlanguage").get(0);
            }
            LanguageCode djLanguage = null;
            if (djLangParam != null && !djLangParam.trim().isEmpty()) {
                djLanguage = LanguageCode.valueOf(djLangParam.trim().toLowerCase());
            }

            String query = rc.queryParam("q").isEmpty() ? null : rc.queryParam("q").get(0);
            if (query == null) {
                query = rc.queryParam("search").isEmpty() ? null : rc.queryParam("search").get(0);
            }
            if (query != null) {
                query = query.trim();
                if (query.isEmpty()) query = null;
            }

            aiHelperService.getAllStations(statuses, country, djLanguage, query)
                    .subscribe().with(
                            (AvailableStationsAiDTO container) -> rc.response()
                                    .setStatusCode(200)
                                    .putHeader("Content-Type", "application/json")
                                    .end(JsonObject.mapFrom(container).encode()),
                            throwable -> {
                                LOGGER.error("Error getting stations list", throwable);
                                rc.response()
                                        .setStatusCode(500)
                                        .putHeader("Content-Type", "text/plain")
                                        .end("An error occurred while retrieving stations");
                            }
                    );
        } catch (IllegalArgumentException e) {
            rc.response()
                    .setStatusCode(400)
                    .putHeader("Content-Type", "text/plain")
                    .end("Invalid query parameters");
        }
    }
}