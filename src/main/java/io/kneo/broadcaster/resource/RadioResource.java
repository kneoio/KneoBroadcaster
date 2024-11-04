package io.kneo.broadcaster.resource;

import io.kneo.broadcaster.stream.HlsPlaylist;
import io.kneo.broadcaster.stream.HlsSegment;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Collections;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Path("/radio")
@ApplicationScoped
public class RadioResource {
    private static final Logger LOGGER = Logger.getLogger(RadioResource.class.getName());

    @Inject
    HlsPlaylist playlist;

    @GET
    @Path("/stream")
    @Produces("application/vnd.apple.mpegurl")
    public Response getPlaylist() {
        LOGGER.info("Playlist request received");

        if (playlist.getSegmentCount() == 0) {
            LOGGER.warning("No segments available in playlist");
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("No segments available")
                    .type(MediaType.TEXT_PLAIN)
                    .build();
        }

        String playlistContent = playlist.generatePlaylist();
        LOGGER.info("Serving playlist with content:\n" + playlistContent);

        return Response.ok(playlistContent)
                .header("Access-Control-Allow-Origin", "*")
                .header("Cache-Control", "no-cache")
                .build();
    }

    @GET
    @Path("/segments/{segment}")
    @Produces("video/MP2T")
    public Response getSegment(@PathParam("segment") String segmentParam) {
        LOGGER.info("Segment request received for: " + segmentParam);

        try {
            int sequence = Integer.parseInt(segmentParam.replaceAll("\\D+", ""));

            HlsSegment segment = playlist.getSegment(sequence);
            if (segment == null) {
                LOGGER.warning("Segment not found: " + segmentParam);
                return Response.status(Response.Status.NOT_FOUND)
                        .entity("Segment not found")
                        .build();
            }

            return Response.ok(segment.getData())
                    .header("Access-Control-Allow-Origin", "*")
                    .header("Cache-Control", "no-cache")
                    .build();

        } catch (NumberFormatException e) {
            LOGGER.severe("Invalid segment name format: " + segmentParam);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Invalid segment name format")
                    .build();
        }
    }

    @GET
    @Path("/debug")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getDebugInfo() {
        return Response.ok(Map.of(
                "segmentCount", playlist.getSegmentCount(),
                "hasPlaylist", playlist != null,
                "segments", playlist.getSegmentCount() > 0 ?
                        IntStream.range(0, playlist.getSegmentCount())
                                .mapToObj(i -> Map.of(
                                        "index", i,
                                        "available", playlist.getSegment(i) != null
                                ))
                                .collect(Collectors.toList()) :
                        Collections.emptyList()
        )).build();
    }
}