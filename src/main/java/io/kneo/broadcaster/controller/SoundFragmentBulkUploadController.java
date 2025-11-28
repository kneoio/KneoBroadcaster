package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.dto.SoundFragmentDTO;
import io.kneo.broadcaster.dto.UploadFileDTO;
import io.kneo.broadcaster.model.soundfragment.SoundFragment;
import io.kneo.broadcaster.service.FileUploadService;
import io.kneo.core.controller.AbstractSecuredController;
import io.kneo.core.repository.exception.UserNotFoundException;
import io.kneo.core.service.UserService;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class SoundFragmentBulkUploadController extends AbstractSecuredController<SoundFragment, SoundFragmentDTO> {
    private static final Logger LOGGER = LoggerFactory.getLogger(SoundFragmentBulkUploadController.class);

    private FileUploadService fileUploadService;
    private Vertx vertx;

    public SoundFragmentBulkUploadController() {
        super(null);
    }

    @Inject
    public SoundFragmentBulkUploadController(UserService userService,
                                             FileUploadService fileUploadService,
                                             Vertx vertx) {
        super(userService);
        this.fileUploadService = fileUploadService;
        this.vertx = vertx;
    }

    public void setupRoutes(Router router) {
        String path = "/api/soundfragments-bulk";
        router.route(HttpMethod.GET, path + "/status/:batchId/stream").handler(this::streamProgress);
        router.route(HttpMethod.POST, path + "/files").handler(this::uploadFile);
    }


    private void streamProgress(RoutingContext rc) {
        String batchId = rc.pathParam("batchId");

        rc.response()
                .putHeader("Content-Type", "text/event-stream")
                .putHeader("Cache-Control", "no-cache")
                .setChunked(true);

        long timerId = vertx.setPeriodic(500, id -> {
            UploadFileDTO progress = fileUploadService.getUploadProgress(batchId);
            if (progress != null && ("finished".equals(progress.getStatus()) || "error".equals(progress.getStatus()))) {
                rc.response().write("data: " + JsonObject.mapFrom(progress).encode() + "\n\n");
                vertx.cancelTimer(id);
                rc.response().end();
            }
        });

        rc.request().connection().closeHandler(v -> vertx.cancelTimer(timerId));
    }

    private void uploadFile(RoutingContext rc) {
        String batchId = rc.request().getParam("batchId");

        if (batchId == null || batchId.trim().isEmpty()) {
            rc.fail(400, new IllegalArgumentException("batchId parameter is required"));
            return;
        }

        getContextUser(rc, false, true)
                .chain(user -> fileUploadService.processDirectStreamAsync(rc, batchId, "sound-fragments-controller", "bulk", user))
                .subscribe().with(
                        dto -> {
                            LOGGER.info("Bulk upload done: {}", batchId);
                            rc.response()
                                    .setStatusCode(200)
                                    .putHeader("Content-Type", "application/json")
                                    .end(io.vertx.core.json.Json.encode(dto));
                        },
                        err -> {
                            LOGGER.error("Bulk upload failed: {}", batchId, err);
                            if (err instanceof IllegalArgumentException e) {
                                int status;
                                if (e.getMessage() != null && e.getMessage().contains("Unsupported")) {
                                    status = 415;
                                } else {
                                    status = 400;
                                }
                                rc.fail(status, e);
                            } else {
                                rc.fail(500, new RuntimeException("Upload failed"));
                            }
                        }
                );
    }

    protected void handleFailure(RoutingContext rc, Throwable throwable) {
        if (throwable instanceof IllegalStateException
                || throwable instanceof IllegalArgumentException
                || throwable instanceof UserNotFoundException) {
            rc.fail(401, throwable);
        } else {
            rc.fail(throwable);
        }
    }
}
