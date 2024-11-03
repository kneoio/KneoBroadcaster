package io.kneo.broadcaster.resource;

import io.kneo.broadcaster.queue.SoundQueueService;
import io.kneo.broadcaster.stream.HlsPlaylist;
import io.kneo.broadcaster.stream.HlsSegment;
import io.quarkus.vertx.web.Route;
import io.quarkus.vertx.web.RouteBase;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.logging.Logger;


@ApplicationScoped
@RouteBase(path = "/radio")
public class RadioResource {
    private static final Logger LOGGER = Logger.getLogger(RadioResource.class.getName());

    @Inject
    SoundQueueService soundQueueService;

    @Route(path = "/stream", methods = Route.HttpMethod.GET)
    void getPlaylist(RoutingContext rc) {
        LOGGER.info("Playlist request received");

        HlsPlaylist playlist = soundQueueService.getPlaylist();
        if (playlist.getSegmentCount() == 0) {
            LOGGER.warning("No segments available in playlist");
            rc.response()
                    .setStatusCode(404)
                    .putHeader("Content-Type", "text/plain")
                    .end("No segments available");
            return;
        }

        String playlistContent = playlist.generatePlaylist();
        LOGGER.info("Serving playlist with content:\n" + playlistContent);

        rc.response()
                .putHeader("Content-Type", "application/vnd.apple.mpegurl")
                .putHeader("Access-Control-Allow-Origin", "*")
                .putHeader("Cache-Control", "no-cache")
                .end(playlistContent);
    }

    @Route(path = "/segments/:segment", methods = Route.HttpMethod.GET)
    void getSegment(RoutingContext rc) {
        String segmentParam = rc.pathParam("segment");
        LOGGER.info("Segment request received for: " + segmentParam);

        try {
            int sequence = Integer.parseInt(segmentParam.replaceAll("\\D+", ""));

            HlsSegment segment = soundQueueService.getPlaylist().getSegment(sequence);
            if (segment == null) {
                LOGGER.warning("Segment not found: " + segmentParam);
                rc.response().setStatusCode(404).end("Segment not found");
                return;
            }

            rc.response()
                    .putHeader("Content-Type", "video/MP2T")
                    .putHeader("Access-Control-Allow-Origin", "*")
                    .putHeader("Cache-Control", "no-cache")
                    .end(io.vertx.core.buffer.Buffer.buffer(segment.getData()));

        } catch (NumberFormatException e) {
            LOGGER.severe("Invalid segment name format: " + segmentParam);
            rc.response().setStatusCode(400).end("Invalid segment name format");
        }
    }
}