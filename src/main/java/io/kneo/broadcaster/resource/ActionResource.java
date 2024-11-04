package io.kneo.broadcaster.resource;

import io.kneo.broadcaster.service.AudioService;
import io.kneo.broadcaster.store.AudioFileStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.PartType;
import org.jboss.resteasy.reactive.RestForm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.InputStream;
import jakarta.ws.rs.core.MultivaluedMap;
import io.kneo.broadcaster.model.SoundFragment;
import org.jboss.resteasy.reactive.multipart.FileUpload;

@Path("/api")
@ApplicationScoped
public class ActionResource {
    private static final Logger LOGGER = LoggerFactory.getLogger(ActionResource.class);

    @Inject
    AudioService audioService;

    @POST
    @Path("/fragments/{id}/action")
    @Produces(MediaType.APPLICATION_JSON)
    public Response performAction(
            @PathParam("id") String idParam,
            @QueryParam("type") String actionType) {
        try {
            int id = Integer.parseInt(idParam);
            if (actionType == null || actionType.isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity("Action type is required")
                        .build();
            }

            switch (actionType.toLowerCase()) {
                case "play":
                    return executePlay(id);
                case "stop":
                    return executeStop(id);
                case "pause":
                    return executePause(id);
                default:
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity("Unknown action type: " + actionType)
                            .build();
            }
        } catch (NumberFormatException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Invalid ID format")
                    .build();
        } catch (Exception e) {
            LOGGER.error("Error performing action", e);
            return Response.serverError()
                    .entity("Error performing action")
                    .build();
        }
    }

    @POST
    @Path("/fragments/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    public Response uploadFile(@RestForm("file") FileUpload file) {
        try {
            if (file == null) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity("File is required")
                        .build();
            }

            SoundFragment savedFragment = audioService.processUploadedFile(file.uploadedFile(), file.fileName());

            return Response.ok(savedFragment)
                    .build();
        } catch (Exception e) {
            LOGGER.error("Error uploading file", e);
            return Response.serverError()
                    .entity("Error uploading file: " + e.getMessage())
                    .build();
        }
    }


    private Response executePlay(int id) {
        try {
            audioService.executeAction(id, "play");
            return Response.ok()
                    .entity("Playing fragment " + id)
                    .build();
        } catch (Exception e) {
            LOGGER.error("Error playing fragment", e);
            return Response.serverError()
                    .entity("Error playing fragment: " + e.getMessage())
                    .build();
        }
    }

    private Response executeStop(int id) {
        try {
            audioService.executeAction(id, "stop");
            return Response.ok()
                    .entity("Stopped fragment " + id)
                    .build();
        } catch (Exception e) {
            LOGGER.error("Error stopping fragment", e);
            return Response.serverError()
                    .entity("Error stopping fragment: " + e.getMessage())
                    .build();
        }
    }

    private Response executePause(int id) {
        try {
            audioService.executeAction(id, "pause");
            return Response.ok()
                    .entity("Paused fragment " + id)
                    .build();
        } catch (Exception e) {
            LOGGER.error("Error pausing fragment", e);
            return Response.serverError()
                    .entity("Error pausing fragment: " + e.getMessage())
                    .build();
        }
    }
}