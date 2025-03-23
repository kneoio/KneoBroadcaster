package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.controller.stream.HLSPlaylist;
import io.kneo.broadcaster.controller.stream.HlsSegment;
import io.kneo.broadcaster.service.RadioService;
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

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class RadioController {
    private static final Logger LOGGER = LoggerFactory.getLogger(RadioController.class);
    // Updated pattern to match three parts: brand_fragmentId_timestamp
    private static final Pattern SEGMENT_PATTERN = Pattern.compile("([^_]+)_([^_]+)_([0-9]+)\\.ts$");
    private final RadioService service;
    private final AtomicInteger listenerCount = new AtomicInteger(0);

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
    }

    private void getPlaylist(RoutingContext rc) {
        String brand = rc.pathParam("brand");
        listenerCount.incrementAndGet();

        LOGGER.debug("Player request PLAYLIST");

        service.getPlaylist(brand)
                .onItem().transform(HLSPlaylist::generatePlaylist)
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
                            listenerCount.decrementAndGet();
                            if (throwable instanceof RadioStationException) {
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

        LOGGER.debug("Player request segment: {}", segmentParam);

        try {
            Matcher matcher = SEGMENT_PATTERN.matcher(segmentParam);
            if (matcher.find()) {
                String stationName = matcher.group(1);
                String fragmentIdStr = matcher.group(2);
                long timestamp = Long.parseLong(matcher.group(3));

                service.getPlaylist(brand)
                        .onItem().transform(playlist -> {
                            // Use both fragment ID and timestamp to find correct segment
                            HlsSegment segment = findSegmentByFragmentAndTimestamp(playlist, fragmentIdStr, timestamp);
                            if (segment == null) {
                                LOGGER.warn("Segment not found: {} with fragment ID: {} and timestamp: {}",
                                        segmentParam, fragmentIdStr, timestamp);
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
            } else {
                LOGGER.warn("Invalid segment name format: {}", segmentParam);
                rc.response()
                        .setStatusCode(400)
                        .putHeader("Content-Type", MediaType.TEXT_PLAIN)
                        .end("Invalid segment name format");
            }
        } catch (NumberFormatException e) {
            LOGGER.warn("Invalid timestamp in segment: {}", segmentParam);
            rc.response()
                    .setStatusCode(400)
                    .putHeader("Content-Type", MediaType.TEXT_PLAIN)
                    .end("Invalid segment name format");
        }
    }

    private HlsSegment findSegmentByFragmentAndTimestamp(HLSPlaylist playlist, String fragmentIdStr, long timestamp) {
        // Get all segments from the playlist
        Set<Long> segmentKeys = playlist.getSegmentKeys();

        // Iterate through all available segments
        for (Long key : segmentKeys) {
            HlsSegment segment = playlist.getSegment(key);

            // Check if this segment matches both fragment ID and timestamp
            if (segment != null &&
                    segment.getTimestamp() == timestamp &&
                    segment.getSoundFragmentId().toString().substring(0, 8).equals(fragmentIdStr)) {

                LOGGER.debug("Found matching segment: key={}, fragmentId={}, timestamp={}",
                        key, fragmentIdStr, timestamp);
                return segment;
            }
        }

        LOGGER.warn("No matching segment found for fragmentId={}, timestamp={}",
                fragmentIdStr, timestamp);
        return null;
    }

    private void getStatus(RoutingContext rc) {
        String brand = rc.pathParam("brand");

        service.getPlaylist(brand)
                .onItem().transform(playlist -> {
                    StringBuilder status = new StringBuilder();
                    status.append("Station: ").append(brand).append("\n");
                    status.append("Active listeners: ").append(listenerCount.get()).append("\n");
                    status.append("Total bytes: ").append(playlist.getTotalBytesProcessed()).append("\n");
                    status.append("Last segment key: ").append(playlist.getLastSegmentKey()).append("\n");
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
}