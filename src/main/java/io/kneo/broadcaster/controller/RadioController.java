package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.controller.stream.HlsSegment;
import io.kneo.broadcaster.controller.stream.IStreamManager;
import io.kneo.broadcaster.service.RadioService;
import io.kneo.broadcaster.service.exceptions.RadioStationException;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.Json;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class RadioController {
    private static final Logger LOGGER = LoggerFactory.getLogger(RadioController.class);
    private final RadioService service;

    @Inject
    public RadioController(RadioService service) {
        this.service = service;
    }

    public void setupRoutes(Router router) {
        String path = "/:brand/radio";
        router.route(HttpMethod.GET, path + "/stream.m3u8").handler(this::getPlaylist);
        router.route(HttpMethod.GET, path + "/stream").handler(this::getPlaylist);
        router.route(HttpMethod.GET, path + "/segments/:segment").handler(this::getSegment);
        router.route(HttpMethod.GET, path + "/status").handler(this::getStatus);
        router.route(HttpMethod.PUT, path + "/wakeup").handler(this::wakeUp);
    }

    private void getPlaylist(RoutingContext rc) {
        String brand = rc.pathParam("brand");
        String userAgent = rc.request().getHeader("User-Agent");
        LOGGER.debug("User-Agent: {}", userAgent);
        service.getPlaylist(brand, userAgent)
                .onItem().transform(IStreamManager::generatePlaylist)
                .subscribe().with(
                        playlistContent -> {
                            rc.response()
                                    .putHeader("Content-Type", "application/vnd.apple.mpegurl")
                                    .putHeader("Cache-Control", "no-cache")
                                    .setStatusCode(200)
                                    .end(playlistContent);
                        },
                        throwable -> {
                            if (throwable instanceof RadioStationException) {
                                LOGGER.warn("Radio station is not on-line for brand: {}", brand);
                                rc.response()
                                        .setStatusCode(404)
                                        .putHeader("Content-Type", MediaType.TEXT_PLAIN)
                                        .end(throwable.getMessage());
                            } else {
                                LOGGER.error("Error serving playlist for brand: {} - {}", brand, throwable.getMessage());
                                rc.fail(throwable);
                            }
                        }
                );
    }

    private void getSegment(RoutingContext rc) {
        String segmentParam = rc.pathParam("segment");
        String brand = rc.pathParam("brand");
        String userAgent = rc.request().getHeader("User-Agent");
        service.getPlaylist(brand, userAgent)
                .onItem().transform(playlist -> {
                    HlsSegment segment = playlist.getSegment(segmentParam);
                    if (segment == null) {
                        LOGGER.warn("Segment not found or invalid: {}", segmentParam);
                        throw new WebApplicationException(Response.Status.NOT_FOUND);
                    }
                    return segment.getData();
                })
                .subscribe().with(
                        data -> {
                            rc.response()
                                    .putHeader("Content-Type", "video/MP2T")
                                    .putHeader("Cache-Control", "no-cache")
                                    .setStatusCode(200)
                                    .end(Buffer.buffer(data));
                        },
                        throwable -> {
                            if (throwable instanceof WebApplicationException e) {
                                rc.response()
                                        .setStatusCode(e.getResponse().getStatus())
                                        .putHeader("Content-Type", MediaType.TEXT_PLAIN)
                                        .end("Segment not found");
                            } else {
                                LOGGER.error("Error serving segment: {} - {}", segmentParam, throwable.getMessage());
                                rc.response()
                                        .setStatusCode(500)
                                        .putHeader("Content-Type", MediaType.TEXT_PLAIN)
                                        .end("Error serving segment");
                            }
                        }
                );
    }

    private void getStatus(RoutingContext rc) {
        String brand = rc.pathParam("brand");
        String userAgent = rc.request().getHeader("User-Agent");

        service.getStatus(brand, userAgent)
                .subscribe().with(
                        statusDto -> {
                            rc.response()
                                    .putHeader("Content-Type", MediaType.APPLICATION_JSON)
                                    .setStatusCode(200)
                                    .end(Json.encode(statusDto));
                        },
                        throwable -> {
                            if (throwable instanceof RadioStationException) {
                                rc.response()
                                        .setStatusCode(404)
                                        .putHeader("Content-Type", MediaType.TEXT_PLAIN)
                                        .end(throwable.getMessage());
                            } else {
                                rc.response()
                                        .setStatusCode(500)
                                        .putHeader("Content-Type", MediaType.TEXT_PLAIN)
                                        .end("Internal server error");
                            }
                        }
                );
    }

    private void wakeUp(RoutingContext rc) {
        String brand = rc.pathParam("brand");
        LOGGER.info("Wake up radio station for brand: {}", brand);
        service.initializeStation(brand)
                .subscribe().with(
                        station -> {
                            rc.response()
                                    .putHeader("Content-Type", MediaType.APPLICATION_JSON)
                                    .putHeader("Access-Control-Allow-Origin", "*")
                                    .setStatusCode(200)
                                    .end();
                        },
                        throwable -> {
                            LOGGER.error("Error wake up radio station: {}", throwable.getMessage());
                            rc.response()
                                    .setStatusCode(500)
                                    .putHeader("Content-Type", MediaType.TEXT_PLAIN)
                                    .end("Failed to wake up radio station: " + throwable.getMessage());
                        }
                );

    }
}