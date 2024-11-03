package io.kneo.broadcaster.resource;

import io.kneo.broadcaster.store.AudioFileStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/api")
@ApplicationScoped
public class StatisticResourceHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(StatisticResourceHandler.class);

    @Inject
    AudioFileStore audioFileStore;

    @GET
    @Path("/fragments")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAllFragments() {
        try {
            var fragments = audioFileStore.getAllFragments();
            return Response.ok(fragments)
                    .header("Access-Control-Allow-Origin", "*")
                    .build();
        } catch (Exception e) {
            LOGGER.error("Error fetching fragments", e);
            return Response.serverError()
                    .entity("Error fetching fragments")
                    .build();
        }
    }

    @GET
    @Path("/fragments/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getFragment(@PathParam("id") String idParam) {
        try {
            int id = Integer.parseInt(idParam);
            var fragment = audioFileStore.getFragment(id);
            if (fragment != null) {
                return Response.ok(fragment)
                        .header("Access-Control-Allow-Origin", "*")
                        .build();
            } else {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity("Fragment not found")
                        .build();
            }
        } catch (NumberFormatException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Invalid ID format")
                    .build();
        } catch (Exception e) {
            LOGGER.error("Error fetching fragment", e);
            return Response.serverError()
                    .entity("Error fetching fragment")
                    .build();
        }
    }

    @GET
    @Path("/fragments/{id}/file")
    @Produces("audio/mpeg")
    public Response getFragmentFile(@PathParam("id") String idParam) {
        try {
            int id = Integer.parseInt(idParam);
            var fragment = audioFileStore.getFragment(id);
            if (fragment != null && fragment.getFile() != null) {
                return Response.ok(fragment.getFile())
                        .header("Content-Disposition", "attachment; filename=\"" + fragment.getName() + "\"")
                        .header("Access-Control-Allow-Origin", "*")
                        .build();
            } else {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity("File not found")
                        .build();
            }
        } catch (NumberFormatException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Invalid ID format")
                    .build();
        } catch (Exception e) {
            LOGGER.error("Error fetching file", e);
            return Response.serverError()
                    .entity("Error fetching file")
                    .build();
        }
    }
}