package io.kneo.broadcaster.resource;

import io.kneo.broadcaster.model.SoundFragment;
import io.kneo.broadcaster.service.AudioService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.annotations.Param;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/api")
@ApplicationScoped
public class ActionResource {
    private static final Logger LOGGER = LoggerFactory.getLogger(ActionResource.class);

    @Inject
    AudioService audioService;

    @GET
    @Path("/action/{action_type}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response performAction(@PathParam("action_type") String actionType) {
        try {
            if (actionType == null || actionType.isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity("Action type is required")
                        .build();
            }

            return Response.ok(audioService.executeAction(actionType))
                    .build();
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
}