package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.dto.SubmissionDTO;
import io.kneo.broadcaster.dto.UploadFileDTO;
import io.kneo.broadcaster.service.FileUploadService;
import io.kneo.broadcaster.service.GeolocationService;
import io.kneo.broadcaster.service.RadioService;
import io.kneo.broadcaster.service.ValidationService;
import io.kneo.broadcaster.service.exceptions.RadioStationException;
import io.kneo.broadcaster.service.stream.HlsSegment;
import io.kneo.broadcaster.service.stream.IStreamManager;
import io.kneo.core.model.user.AnonymousUser;
import io.kneo.core.repository.exception.DocumentModificationAccessException;
import io.kneo.core.repository.exception.UploadAbsenceException;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.kneo.officeframe.cnst.CountryCode;
import java.nio.file.Files;
import java.nio.file.Path;

@ApplicationScoped
public class RadioController {
    private static final Logger LOGGER = LoggerFactory.getLogger(RadioController.class);
    private final RadioService service;
    private static final String[] SUPPORTED_MIXPLA_VERSIONS = {"2.5.5", "2.5.6", "2.5.7", "2.5.8", "2.5.9"};
    private final ValidationService validationService;
    private final FileUploadService fileUploadService;
    private final Vertx vertx;
    private static final long BODY_HANDLER_LIMIT = 1024L * 1024L * 1024L;

    @Inject
    public RadioController(RadioService service, ValidationService validationService, FileUploadService fileUploadService, Vertx vertx) {
        this.service = service;
        this.validationService = validationService;
        this.fileUploadService = fileUploadService;
        this.vertx = vertx;
    }

    @Inject
    private GeolocationService geoService;

    public void setupRoutes(Router router) {
        String path = "/:brand/radio";

        BodyHandler jsonBodyHandler = BodyHandler.create().setHandleFileUploads(false);

        router.route(HttpMethod.GET, path + "/stream.m3u8").handler(this::getPlaylist);
        router.route(HttpMethod.GET, path + "/stream").handler(this::getSegment);
        router.route(HttpMethod.GET, path + "/segments/:segment").handler(this::getSegment);
        router.route(HttpMethod.GET, path + "/status").handler(this::getStatus);
        router.route(HttpMethod.PUT, path + "/wakeup").handler(this::wakeUp);

        router.route(HttpMethod.GET, "/radio/stations").handler(this::validateMixplaAccess).handler(this::getStations);
        router.route(HttpMethod.GET, "/radio/all-stations").handler(this::validateMixplaAccess).handler(this::getAllStations);

        router.route(HttpMethod.GET,  "/radio/:brand/submissions/files/:uploadId/stream").handler(this::streamProgress);
        router.route(HttpMethod.POST, "/radio/:brand/submissions").handler(this::validateMixplaAccess).handler(this::submit);  //to save
        router.route(HttpMethod.POST, "/radio/:brand/submissions/files/start").handler(jsonBodyHandler).handler(this::startUploadSession);  //to start sse progress feedback
        router.route(HttpMethod.POST, "/radio/:brand/submissions/files/:id")
                .order(-100)
                .handler(this::uploadFile); // streaming upload
    }

    private void getPlaylist(RoutingContext rc) {
        String brand = rc.pathParam("brand").toLowerCase();
        String userAgent = rc.request().getHeader("User-Agent");
        String clientIP = rc.request().getHeader("stream-connecting-ip");
        //LOGGER.info(">>>>>>>>>>>>>>> client IP {}", clientIP);
        //LOGGER.info("All headers: {}", rc.request().headers().names());
        geoService.recordAccessWithGeolocation(brand, userAgent, clientIP)
                .chain(country -> service.getPlaylist(brand, userAgent, false))
                .onItem().transform(IStreamManager::generatePlaylist)
                .subscribe().with(
                        playlistContent -> {
                            rc.response()
                                    .putHeader("Content-Type", "application/vnd.apple.mpegurl")
                                    .putHeader("Cache-Control", "no-cache")
                                    .setStatusCode(200)
                                    .end(playlistContent);
                        },
                        throwable -> {
                            if (throwable instanceof RadioStationException) {
                                LOGGER.warn("Radio station is not on-line for brand: {}", brand);
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
        String brand = rc.pathParam("brand").toLowerCase();
        String userAgent = rc.request().getHeader("User-Agent");

        service.getPlaylist(brand, userAgent, false)
                .onItem().transform(playlist -> {
                    HlsSegment segment = playlist.getSegment(segmentParam);
                    if (segment == null) {
                        LOGGER.warn("Segment not found or invalid: {}", segmentParam);
                        throw new WebApplicationException(Response.Status.NOT_FOUND);
                    }
                    return segment.getData();
                })
                .subscribe().with(
                        data -> {
                            rc.response()
                                    .putHeader("Content-Type", "video/MP2T")
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
    }

    private void getStatus(RoutingContext rc) {
        String brand = rc.pathParam("brand").toLowerCase();
        String userAgent = rc.request().getHeader("User-Agent");

        service.getStatus(brand, userAgent)
                .subscribe().with(
                        statusDto -> {
                            rc.response()
                                    .putHeader("Content-Type", MediaType.APPLICATION_JSON)
                                    .setStatusCode(200)
                                    .end(Json.encode(statusDto));
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

    private void wakeUp(RoutingContext rc) {
        String brand = rc.pathParam("brand").toLowerCase();
        LOGGER.info("Wake up radio station for brand: {}", brand);
        service.initializeStation(brand)
                .subscribe().with(
                        station -> {
                            rc.response()
                                    .putHeader("Content-Type", MediaType.APPLICATION_JSON)
                                    .setStatusCode(200)
                                    .end();
                        },
                        throwable -> {
                            LOGGER.error("Error wake up radio station: {}", throwable.getMessage());
                            rc.response()
                                    .setStatusCode(500)
                                    .putHeader("Content-Type", MediaType.TEXT_PLAIN)
                                    .end("Failed to wake up radio station: " + throwable.getMessage());
                        }
                );
    }

    private void getStations(RoutingContext rc) {
        service.getStations()
                .subscribe().with(
                        stations -> {
                            rc.response()
                                    .putHeader("Content-Type", MediaType.APPLICATION_JSON)
                                    .setStatusCode(200)
                                    .end(Json.encode(stations));
                        },
                        throwable -> {
                            LOGGER.error("Error getting stations list: {}", throwable.getMessage());
                            rc.response()
                                    .setStatusCode(500)
                                    .putHeader("Content-Type", MediaType.TEXT_PLAIN)
                                    .end("Failed to get stations list: " + throwable.getMessage());
                        }
                );
    }

    private void getAllStations(RoutingContext rc) {
        service.getAllStations()
                .subscribe().with(
                        stations -> {
                            rc.response()
                                    .putHeader("Content-Type", MediaType.APPLICATION_JSON)
                                    .setStatusCode(200)
                                    .end(Json.encode(stations));
                        },
                        throwable -> {
                            LOGGER.error("Error getting all stations: {}", throwable.getMessage());
                            rc.response()
                                    .setStatusCode(500)
                                    .putHeader("Content-Type", MediaType.TEXT_PLAIN)
                                    .end("Failed to get all stations: " + throwable.getMessage());
                        }
                );
    }

    private void submit(RoutingContext rc) {
        String clientIPHeader = rc.request().getHeader("stream-connecting-ip");
        try {
            if (!validateJsonBody(rc)) {
                return;
            }

            SubmissionDTO dto = rc.body().asJsonObject().mapTo(SubmissionDTO.class);
            String[] ipCountryParts = GeolocationService.parseIPHeader(clientIPHeader);
            String clientIP = ipCountryParts[0];
            String country = ipCountryParts[1];
            dto.setIpAddress(clientIP);
            dto.setCountry(CountryCode.valueOf(country));
            dto.setUserAgent(rc.request().getHeader("User-Agent"));
            String brand = rc.pathParam("brand");

            validationService.validateSubmissionDTO(dto)
                    .chain(validationResult -> {
                        if (!validationResult.isValid()) {
                            return Uni.createFrom().failure(new IllegalArgumentException(validationResult.getErrorMessage()));
                        }
                        return service.submit(brand, dto);
                    })
                    .subscribe().with(
                            doc -> rc.response().setStatusCode(200).end(doc.toString()),
                            throwable -> {
                                if (throwable instanceof IllegalArgumentException) {
                                    rc.fail(400, throwable);
                                } else if (throwable instanceof DocumentModificationAccessException) {
                                    rc.response().setStatusCode(403).end("Not enough rights to update");
                                } else if (throwable instanceof UploadAbsenceException) {
                                    rc.response().setStatusCode(400).end(throwable.getMessage());
                                } else {
                                    rc.fail(throwable);
                                }
                            }
                    );

        } catch (Exception e) {
            if (e instanceof IllegalArgumentException) {
                rc.fail(400, e);
            } else {
                rc.fail(400, new IllegalArgumentException("Invalid JSON payload"));
            }
        }
    }

    private void validateMixplaAccess(RoutingContext rc) {
        String referer = rc.request().getHeader("Referer");
        String clientId = rc.request().getHeader("X-Client-ID");
        String mixplaApp = rc.request().getHeader("X-Mixpla-App");

        if (mixplaApp != null && isValidMixplaApp(mixplaApp)) {
            rc.next();
            return;
        }

        if (referer != null &&
                (referer.equals("https://mixpla.io/") || referer.equals("http://localhost:8090/"))  &&
                clientId != null && clientId.equals("mixpla-web")) {
            rc.next();
            return;
        }

        LOGGER.warn("Invalid Mixpla access from IP: {}", rc.request().remoteAddress());
        rc.response()
                .setStatusCode(403)
                .putHeader("Content-Type", MediaType.TEXT_PLAIN)
                .end("Access denied");
    }

    private boolean isValidMixplaApp(String mixplaApp) {
        if (!mixplaApp.startsWith("Mixpla/")) {
            return false;
        }

        String version = mixplaApp.substring(7);
        for (String supportedVersion : SUPPORTED_MIXPLA_VERSIONS) {
            if (supportedVersion.equals(version)) {
                return true;
            }
        }

        LOGGER.warn("Unsupported Mixpla app version: {}", version);
        return false;
    }

    protected boolean validateJsonBody(RoutingContext rc) {
        JsonObject json = rc.body().asJsonObject();
        if (json == null) {
            rc.response().setStatusCode(400).end("Request body must be a valid JSON object");
            return false;
        } else {
            return true;
        }
    }

    private void startUploadSession(RoutingContext rc) {
        String uploadId = rc.request().getParam("uploadId");
        String clientStartTimeStr = rc.request().getParam("startTime");

        if (uploadId == null || uploadId.trim().isEmpty()) {
            rc.fail(400, new IllegalArgumentException("uploadId parameter is required"));
            return;
        }

        if (clientStartTimeStr == null || clientStartTimeStr.trim().isEmpty()) {
            rc.fail(400, new IllegalArgumentException("startTime parameter is required"));
            return;
        }

        UploadFileDTO uploadDto = fileUploadService.createUploadSession(uploadId, clientStartTimeStr);
        rc.response()
                .putHeader("Content-Type", "application/json")
                .end(JsonObject.mapFrom(uploadDto).encode());

        LOGGER.info("Upload session started for uploadId: {}", uploadId);
    }

    private void uploadFile(RoutingContext rc) {
        String entityId = rc.pathParam("id");
        String uploadId = rc.request().getParam("uploadId");

        if (uploadId == null || uploadId.trim().isEmpty()) {
            rc.fail(400, new IllegalArgumentException("uploadId parameter is required"));
            return;
        }

        try {
            rc.request().setExpectMultipart(true);
        } catch (IllegalStateException ignore) {
        }
        rc.response().setStatusCode(202).end();

        rc.request().uploadHandler(upload -> {
            try {
                fileUploadService.validateUploadMeta(upload.filename(), upload.contentType());

                Path tempFile = Files.createTempFile("radio-upload-" + uploadId + "-", ".tmp");
                String tempPath = tempFile.toString();

                upload.streamToFileSystem(tempPath);

                upload.endHandler(v -> fileUploadService
                        .processStreamedTempFile(tempPath, uploadId, entityId, AnonymousUser.build(), upload.filename())
                        .subscribe().with(
                                success -> LOGGER.info("Upload done: {}", uploadId),
                                error -> LOGGER.error("Upload failed: {}", uploadId, error)
                        )
                );

                upload.exceptionHandler(err -> {
                    LOGGER.error("Upload stream failed: {}", uploadId, err);
                });
            } catch (IllegalArgumentException e) {
                int statusCode = e.getMessage() != null && e.getMessage().contains("Unsupported") ? 415 : 400;
                rc.fail(statusCode, e);
            } catch (Exception e) {
                rc.fail(e);
            }
        });
    }

    private void streamProgress(RoutingContext rc) {
        String uploadId = rc.pathParam("uploadId");

        rc.response()
                .putHeader("Content-Type", "text/event-stream")
                .putHeader("Cache-Control", "no-cache")
                .setChunked(true);

        UploadFileDTO initial = fileUploadService.getUploadProgress(uploadId);
        if (initial != null) {
            rc.response().write("data: " + JsonObject.mapFrom(initial).encode() + "\n\n");
        } else {
            UploadFileDTO waiting = UploadFileDTO.builder()
                    .status("pending")
                    .percentage(0)
                    .batchId(uploadId)
                    .build();
            rc.response().write("data: " + JsonObject.mapFrom(waiting).encode() + "\n\n");
        }

        long timerId = vertx.setPeriodic(500, id -> {
            UploadFileDTO progress = fileUploadService.getUploadProgress(uploadId);
            if (progress != null) {
                rc.response().write("data: " + JsonObject.mapFrom(progress).encode() + "\n\n");

                if ("finished".equals(progress.getStatus()) || "error".equals(progress.getStatus())) {
                    vertx.cancelTimer(id);
                    rc.response().end();
                }
            }
        });

        rc.request().connection().closeHandler(v -> {
            vertx.cancelTimer(timerId);
        });
    }
}