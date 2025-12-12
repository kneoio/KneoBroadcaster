package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.dto.aihelper.llmtool.AvailableStationsAiDTO;
import io.kneo.broadcaster.dto.aihelper.llmtool.ListenerAiDTO;
import io.kneo.broadcaster.dto.aihelper.llmtool.LiveRadioStationStatAiDTO;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.service.live.AiHelperService;
import io.kneo.broadcaster.service.live.AirSupplier;
import io.kneo.core.localization.LanguageCode;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class AiHelperController {
    private static final Logger LOGGER = LoggerFactory.getLogger(AiHelperController.class);

    private final AiHelperService aiHelperService;
    private final AirSupplier airSupplier;

    public AiHelperController(AiHelperService aiHelperService, AirSupplier airSupplier) {
        this.aiHelperService = aiHelperService;
        this.airSupplier = airSupplier;
    }

    public void setupRoutes(Router router) {
        router.get("/api/ai/live/stations").handler(this::getLiveRadioStations);
        router.get("/api/ai/station/:slug/live").handler(this::getStationLiveStat);
        router.get("/api/ai/listener/by-telegram-name/:name").handler(this::getListenerByTelegramName);
        router.get("/api/ai/stations").handler(this::getAllStations);
        router.get("/api/ai/brand/:brand/soundfragments").handler(this::getBrandSoundFragments);
   }


    private void getLiveRadioStations(RoutingContext rc) {
        String statusesParam = rc.queryParam("statuses").isEmpty() ? null : rc.queryParam("statuses").getFirst();
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

            airSupplier.getOnline(statuses)
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
            String statusesParam = rc.queryParam("statuses").isEmpty() ? null : rc.queryParam("statuses").getFirst();
            List<RadioStationStatus> statuses = null;
            if (statusesParam != null && !statusesParam.trim().isEmpty()) {
                statuses = Arrays.stream(statusesParam.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(RadioStationStatus::valueOf)
                        .collect(Collectors.toList());
            }

            String country = rc.queryParam("country").isEmpty() ? null : rc.queryParam("country").getFirst();
            if (country != null) {
                country = country.trim();
                if (country.isEmpty()) country = null;
            }

            String djLangParam = rc.queryParam("djLanguage").isEmpty() ? null : rc.queryParam("djLanguage").getFirst();
            if (djLangParam == null) {
                djLangParam = rc.queryParam("djlanguage").isEmpty() ? null : rc.queryParam("djlanguage").getFirst();
            }
            LanguageCode djLanguage = null;
            if (djLangParam != null && !djLangParam.trim().isEmpty()) {
                djLanguage = LanguageCode.valueOf(djLangParam.trim().toLowerCase());
            }

            String query = rc.queryParam("q").isEmpty() ? null : rc.queryParam("q").getFirst();
            if (query == null) {
                query = rc.queryParam("search").isEmpty() ? null : rc.queryParam("search").getFirst();
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

    private void getBrandSoundFragments(RoutingContext rc) {
        String brand = rc.pathParam("brand");
        if (brand == null || brand.trim().isEmpty()) {
            rc.response()
                    .setStatusCode(400)
                    .putHeader("Content-Type", "text/plain")
                    .end("Brand parameter is required");
            return;
        }

        String keyword = rc.queryParam("keyword").isEmpty() ? null : rc.queryParam("keyword").getFirst();
        if (keyword == null || keyword.trim().isEmpty()) {
            rc.response()
                    .setStatusCode(400)
                    .putHeader("Content-Type", "text/plain")
                    .end("Keyword parameter is required");
            return;
        }

        try {
            Integer limit = null;
            String limitParam = rc.queryParam("limit").isEmpty() ? null : rc.queryParam("limit").getFirst();
            if (limitParam != null && !limitParam.trim().isEmpty()) {
                limit = Integer.parseInt(limitParam.trim());
            }

            Integer offset = null;
            String offsetParam = rc.queryParam("offset").isEmpty() ? null : rc.queryParam("offset").getFirst();
            if (offsetParam != null && !offsetParam.trim().isEmpty()) {
                offset = Integer.parseInt(offsetParam.trim());
            }

            aiHelperService.searchBrandSoundFragmentsForAi(brand.trim(), keyword.trim(), limit, offset)
                    .subscribe().with(
                            fragments -> rc.response()
                                    .setStatusCode(200)
                                    .putHeader("Content-Type", "application/json")
                                    .end(Json.encode(fragments)),
                            throwable -> {
                                LOGGER.error("Error searching brand sound fragments for brand: {}", brand, throwable);
                                rc.response()
                                        .setStatusCode(500)
                                        .putHeader("Content-Type", "text/plain")
                                        .end("An error occurred while searching brand sound fragments");
                            }
                    );
        } catch (NumberFormatException e) {
            rc.response()
                    .setStatusCode(400)
                    .putHeader("Content-Type", "text/plain")
                    .end("Invalid limit or offset parameter");
        } catch (Exception e) {
            LOGGER.error("Error processing brand sound fragments search request", e);
            rc.response()
                    .setStatusCode(400)
                    .putHeader("Content-Type", "text/plain")
                    .end("Invalid query parameters");
        }
    }
}