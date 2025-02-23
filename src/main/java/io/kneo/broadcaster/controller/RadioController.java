package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.controller.stream.HlsSegment;
import io.kneo.broadcaster.service.RadioService;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

@ApplicationScoped
public class RadioController {
    private static final Logger LOGGER = Logger.getLogger(RadioController.class.getName());

    private final RadioService service;

    @Inject
    public RadioController(RadioService service) {
        this.service = service;
    }

    private final AtomicInteger listenerCount = new AtomicInteger(0);


    public void setupRoutes(Router router) {
        String path = "/:brand/radio";
        router.route(HttpMethod.GET, path + "/stream").handler(this::getPlaylist);
        router.route(HttpMethod.GET, path + "/segments/:segment").handler(this::getSegment);
    }

    private void getPlaylist(RoutingContext rc) {
        String brand = rc.pathParam("brand");
        LOGGER.info("Playlist request received for brand: " + brand);
        listenerCount.incrementAndGet();
        LOGGER.info("Listener count: " + listenerCount.get());
        service.getPlaylist(brand)
                .onItem().transform(playlist -> {
                    if (playlist.getSegmentCount() == 0) {
                        LOGGER.warning("No segments available in playlist for brand: " + brand);
                        throw new WebApplicationException(Response.Status.NOT_FOUND);
                    }
                    return playlist.generatePlaylist();
                })
                .subscribe().with(
                        playlistContent -> {
                            LOGGER.info("Serving playlist for brand: " + brand + " with content:\n" + playlistContent);
                            rc.response()
                                    .putHeader("Content-Type", "application/vnd.apple.mpegurl")
                                    .putHeader("Access-Control-Allow-Origin", "*")
                                    .putHeader("Cache-Control", "no-cache")
                                    .setStatusCode(200)
                                    .end(playlistContent);
                        },
                        throwable -> {
                            listenerCount.decrementAndGet();
                            LOGGER.info("Listener count: " + listenerCount.get());
                            if (throwable instanceof WebApplicationException) {
                                rc.response()
                                        .setStatusCode(404)
                                        .putHeader("Content-Type", MediaType.TEXT_PLAIN)
                                        .end("No segments available");
                            } else {
                                LOGGER.severe("Error serving playlist for brand: " + brand + " - " + throwable.getMessage());
                                rc.fail(throwable);
                            }
                        }
                );
    }

    private void getSegment(RoutingContext rc) {
        String segmentParam = rc.pathParam("segment");
        String brand = rc.pathParam("brand");
        LOGGER.info("Segment request received for brand: " + brand + ", segment: " + segmentParam);

        try {
            int sequence = Integer.parseInt(segmentParam.replaceAll("\\D+", ""));
            service.getPlaylist(brand)
                    .onItem().transform(playlist -> {
                        HlsSegment segment = playlist.getSegment(sequence);
                        if (segment == null) {
                            LOGGER.warning("Segment not found for brand: " + brand + ", sequence: " + sequence);
                            throw new WebApplicationException(Response.Status.NOT_FOUND);
                        }
                        return segment.getData();
                    })
                    .subscribe().with(
                            data -> {
                                LOGGER.info("Serving segment for brand: " + brand + ", sequence: " + sequence);
                                rc.response()
                                        .putHeader("Content-Type", "video/MP2T")
                                        .putHeader("Access-Control-Allow-Origin", "*")
                                        .putHeader("Cache-Control", "no-cache")
                                        .setStatusCode(200)
                                        .end(Buffer.buffer(data));
                            },
                            throwable -> {
                                listenerCount.decrementAndGet(); // Decrement listener count on error
                                LOGGER.info("Listener count: " + listenerCount.get());
                                if (throwable instanceof WebApplicationException) {
                                    WebApplicationException e = (WebApplicationException) throwable;
                                    String message = e.getResponse().getStatus() == 404 ?
                                            "Segment not found" : "Invalid segment name format";

                                    rc.response()
                                            .setStatusCode(e.getResponse().getStatus())
                                            .putHeader("Content-Type", MediaType.TEXT_PLAIN)
                                            .end(message);
                                } else {
                                    listenerCount.decrementAndGet();
                                    LOGGER.severe("Error serving segment for brand: " + brand + ", sequence: " + sequence + " - " + throwable.getMessage());
                                    rc.fail(throwable);
                                }
                            }
                    );
        } catch (NumberFormatException e) {
            LOGGER.severe("Invalid segment name format: " + segmentParam);
            rc.response()
                    .setStatusCode(400)
                    .putHeader("Content-Type", MediaType.TEXT_PLAIN)
                    .end("Invalid segment name format");
        }
    }
}