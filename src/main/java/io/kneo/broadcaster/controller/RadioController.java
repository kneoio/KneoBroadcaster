package io.kneo.broadcaster.controller;


import io.kneo.broadcaster.controller.stream.HlsSegment;
import io.kneo.broadcaster.service.RadioService;
import io.smallrye.mutiny.Uni;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.logging.Logger;

@ApplicationScoped
public class RadioController {
    private static final Logger LOGGER = Logger.getLogger(RadioController.class.getName());

    RadioService service;

    public RadioController() {

    }

    @Inject
    public RadioController(RadioService service) {
        this.service = service;
    }

    public void setupRoutes(Router router) {
        String path = "/:brand/radio";
        router.route(HttpMethod.GET, path + "/stream").handler(this::getPlaylist);
        router.route(HttpMethod.GET, path + "/segments/:segment").handler(this::getSegment);
    }

    private void getPlaylist(RoutingContext rc) {
        String brand = rc.pathParam("brand");
        LOGGER.info("Playlist request received for brand: " + brand);

        Uni.createFrom().item(() -> {
                    if (service.getPlaylist(brand).getSegmentCount() == 0) {
                        LOGGER.warning("No segments available in playlist");
                        throw new WebApplicationException(Response.Status.NOT_FOUND);
                    }
                    return service.getPlaylist(brand).generatePlaylist();
                })
                .subscribe().with(
                        playlistContent -> {
                            LOGGER.info("Serving playlist with content:\n" + playlistContent);
                            rc.response()
                                    .putHeader("Content-Type", "application/vnd.apple.mpegurl")
                                    .putHeader("Access-Control-Allow-Origin", "*")
                                    .putHeader("Cache-Control", "no-cache")
                                    .setStatusCode(200)
                                    .end(playlistContent);
                        },
                        throwable -> {
                            if (throwable instanceof WebApplicationException) {
                                rc.response()
                                        .setStatusCode(404)
                                        .putHeader("Content-Type", MediaType.TEXT_PLAIN)
                                        .end("No segments available");
                            } else {
                                rc.fail(throwable);
                            }
                        }
                );
    }

    private void getSegment(RoutingContext rc) {
        String segmentParam = rc.pathParam("segment");
        String brand = rc.pathParam("brand");
        LOGGER.info("Segment brand: " + brand + " " + segmentParam);

        Uni.createFrom().item(() -> {
                    try {
                        int sequence = Integer.parseInt(segmentParam.replaceAll("\\D+", ""));
                        HlsSegment segment = service.getPlaylist(brand).getSegment(sequence);

                        if (segment == null) {
                            LOGGER.warning("Segment not found: " + segmentParam);
                            throw new WebApplicationException(Response.Status.NOT_FOUND);
                        }

                        return segment.getData();
                    } catch (NumberFormatException e) {
                        LOGGER.severe("Invalid segment name format: " + segmentParam);
                        throw new WebApplicationException(Response.Status.BAD_REQUEST);
                    }
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
                            if (throwable instanceof WebApplicationException) {
                                WebApplicationException e = (WebApplicationException) throwable;
                                String message = e.getResponse().getStatus() == 404 ?
                                        "Segment not found" : "Invalid segment name format";

                                rc.response()
                                        .setStatusCode(e.getResponse().getStatus())
                                        .putHeader("Content-Type", MediaType.TEXT_PLAIN)
                                        .end(message);
                            } else {
                                rc.fail(throwable);
                            }
                        }
                );
    }
}
