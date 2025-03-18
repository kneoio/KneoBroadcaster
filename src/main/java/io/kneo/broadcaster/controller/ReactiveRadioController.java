package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.controller.stream.HlsSegment;
import io.kneo.broadcaster.controller.stream.ReactiveHlsPlaylist;
import io.kneo.broadcaster.service.ReactiveRadioService;
import io.kneo.broadcaster.service.exceptions.RadioStationException;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Controller for handling HLS radio streaming requests
 * Uses reactive programming patterns for better performance
 */
@ApplicationScoped
public class ReactiveRadioController {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReactiveRadioController.class);

    // Pattern to extract timestamp from segment name
    private static final Pattern SEGMENT_PATTERN = Pattern.compile("(\\d+)\\.ts$");

    private final ReactiveRadioService radioService;
    private final AtomicInteger activeListeners = new AtomicInteger(0);

    @Inject
    public ReactiveRadioController(ReactiveRadioService radioService) {
        this.radioService = radioService;
    }

    /**
     * Set up the Vert.x routes for HLS streaming
     */
    public void setupRoutes(Router router) {
        String path = "/:brand/radio";
        router.route(HttpMethod.GET, path + "/stream.m3u8").handler(this::getPlaylist);
        router.route(HttpMethod.GET, path + "/segments/:segment").handler(this::getSegment);
        router.route(HttpMethod.GET, path + "/status").handler(this::getStatus);
    }

    /**
     * Handle playlist requests
     */
    private void getPlaylist(RoutingContext rc) {
        String brand = rc.pathParam("brand");
        activeListeners.incrementAndGet();

        LOGGER.debug("Playlist requested for brand: {}", brand);

        // Get the playlist and generate the M3U8 content
        radioService.getPlaylist(brand)
                .onItem().transform(ReactiveHlsPlaylist::generatePlaylist)
                .subscribe().with(
                        playlistContent -> {
                            rc.response()
                                    .putHeader("Content-Type", "application/vnd.apple.mpegurl")
                                    .putHeader("Access-Control-Allow-Origin", "*")
                                    .putHeader("Cache-Control", "no-cache")
                                    .setStatusCode(200)
                                    .end(playlistContent);
                        },
                        throwable -> {
                            activeListeners.decrementAndGet();
                            handleError(rc, brand, throwable);
                        }
                );
    }

    /**
     * Handle segment requests
     */
    private void getSegment(RoutingContext rc) {
        String segmentParam = rc.pathParam("segment");
        String brand = rc.pathParam("brand");

        LOGGER.debug("Segment requested for brand: {}, segment: {}", brand, segmentParam);

        try {
            // Extract timestamp from segment name (station_TIMESTAMP.ts)
            Matcher matcher = SEGMENT_PATTERN.matcher(segmentParam);
            if (!matcher.find()) {
                // Use fallback regex to try extracting any number
                String[] parts = segmentParam.split("_");
                String timestampStr = parts[parts.length - 1].replace(".ts", "");

                long timestamp = Long.parseLong(timestampStr);

                radioService.getPlaylist(brand)
                        .onItem().transform(playlist -> {
                            HlsSegment segment = playlist.getSegment(timestamp);
                            if (segment == null) {
                                LOGGER.warn("Segment not found for brand: {}, timestamp: {}", brand, timestamp);
                                throw new WebApplicationException(Response.Status.NOT_FOUND);
                            }
                            return segment.getData();
                        })
                        .subscribe().with(
                                data -> {
                                    rc.response()
                                            .putHeader("Content-Type", "video/MP2T")
                                            .putHeader("Access-Control-Allow-Origin", "*")
                                            .putHeader("Cache-Control", "no-cache")
                                            .setStatusCode(200)
                                            .end(Buffer.buffer(data));
                                },
                                throwable -> handleError(rc, brand, throwable)
                        );
            } else {
                rc.response()
                        .setStatusCode(400)
                        .putHeader("Content-Type", MediaType.TEXT_PLAIN)
                        .end("Invalid segment name format");
            }
        } catch (NumberFormatException e) {
            LOGGER.error("Invalid segment name format: {}", segmentParam);
            rc.response()
                    .setStatusCode(400)
                    .putHeader("Content-Type", MediaType.TEXT_PLAIN)
                    .end("Invalid segment name format");
        }
    }

    /**
     * Handle status requests
     */
    private void getStatus(RoutingContext rc) {
        String brand = rc.pathParam("brand");

        radioService.getPlaylist(brand)
                .onItem().transform(playlist -> {
                    StringBuilder status = new StringBuilder();
                    status.append("Station: ").append(brand).append("\n");
                    status.append("Active listeners: ").append(activeListeners.get()).append("\n");
                    status.append("Segment count: ").append(playlist.getSegmentCount()).append("\n");
                    status.append("Total bytes: ").append(playlist.getTotalBytesProcessed()).append("\n");

                    return status.toString();
                })
                .subscribe().with(
                        statusContent -> {
                            rc.response()
                                    .putHeader("Content-Type", MediaType.TEXT_PLAIN)
                                    .putHeader("Access-Control-Allow-Origin", "*")
                                    .setStatusCode(200)
                                    .end(statusContent);
                        },
                        throwable -> handleError(rc, brand, throwable)
                );
    }

    /**
     * Common error handling for all endpoints
     */
    private void handleError(RoutingContext rc, String brand, Throwable throwable) {
        if (throwable instanceof RadioStationException) {
            LOGGER.warn("Radio station error for brand {}: {}", brand, throwable.getMessage());
            rc.response()
                    .setStatusCode(404)
                    .putHeader("Content-Type", MediaType.TEXT_PLAIN)
                    .end(throwable.getMessage());
        } else if (throwable instanceof WebApplicationException e) {
            LOGGER.warn("Web application error for brand {}: {}", brand, e.getMessage());
            String message = e.getResponse().getStatus() == 404 ?
                    "Segment not found" : "Invalid request";

            rc.response()
                    .setStatusCode(e.getResponse().getStatus())
                    .putHeader("Content-Type", MediaType.TEXT_PLAIN)
                    .end(message);
        } else {
            LOGGER.error("Unexpected error for brand {}: {}", brand, throwable.getMessage(), throwable);
            rc.response()
                    .setStatusCode(500)
                    .putHeader("Content-Type", MediaType.TEXT_PLAIN)
                    .end("Internal server error");
        }
    }
}