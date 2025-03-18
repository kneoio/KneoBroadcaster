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

import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class RadioController {
    private static final Logger LOGGER = LoggerFactory.getLogger(RadioController.class);
    private static final Pattern SEGMENT_PATTERN = Pattern.compile("([^_]+)_([0-9]+)\\.ts$");
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

        try {
            Matcher matcher = SEGMENT_PATTERN.matcher(segmentParam);
            if (matcher.find()) {
                String stationName = matcher.group(1);
                long timestamp = Long.parseLong(matcher.group(2));

                service.getPlaylist(brand)
                        .onItem().transform(playlist -> {
                            HlsSegment segment = findSegmentByTimestamp(playlist, timestamp);
                            if (segment == null) {
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
                                    listenerCount.decrementAndGet();
                                    if (throwable instanceof WebApplicationException e) {
                                        rc.response()
                                                .setStatusCode(e.getResponse().getStatus())
                                                .putHeader("Content-Type", MediaType.TEXT_PLAIN)
                                                .end("Segment not found");
                                    } else {
                                        rc.response()
                                                .setStatusCode(500)
                                                .putHeader("Content-Type", MediaType.TEXT_PLAIN)
                                                .end("Error serving segment");
                                    }
                                }
                        );
            } else {
                rc.response()
                        .setStatusCode(400)
                        .putHeader("Content-Type", MediaType.TEXT_PLAIN)
                        .end("Invalid segment name format");
            }
        } catch (NumberFormatException e) {
            rc.response()
                    .setStatusCode(400)
                    .putHeader("Content-Type", MediaType.TEXT_PLAIN)
                    .end("Invalid segment name format");
        }
    }

    private HlsSegment findSegmentByTimestamp(HLSPlaylist playlist, long timestamp) {
        for (int i = 0; i < playlist.getSegmentCount(); i++) {
            long key = playlist.getLastSegmentKey() - i;
            if (key < 0) break;

            HlsSegment segment = playlist.getSegment(key);
            if (segment != null && segment.getTimestamp() == timestamp) {
                return segment;
            }
        }
        return null;
    }

    private void getStatus(RoutingContext rc) {
        String brand = rc.pathParam("brand");

        service.getPlaylist(brand)
                .onItem().transform(playlist -> {
                    StringBuilder status = new StringBuilder();
                    status.append("Station: ").append(brand).append("\n");
                    status.append("Active listeners: ").append(listenerCount.get()).append("\n");
                    status.append("Segment count: ").append(playlist.getSegmentCount()).append("\n");
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