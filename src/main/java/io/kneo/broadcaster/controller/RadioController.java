package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.dto.radio.MessageDTO;
import io.kneo.broadcaster.dto.radio.SubmissionDTO;
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
import io.kneo.officeframe.cnst.CountryCode;
import io.smallrye.mutiny.Uni;
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

import java.util.List;

@ApplicationScoped
public class RadioController {
    private static final Logger LOGGER = LoggerFactory.getLogger(RadioController.class);
    private final RadioService service;
    private static final String[] SUPPORTED_MIXPLA_VERSIONS = {"2.5.5", "2.5.6", "2.5.7", "2.5.8", "2.5.9"};
    private final ValidationService validationService;
    private final FileUploadService fileUploadService;
    private static final long BODY_HANDLER_LIMIT = 1024L * 1024L * 1024L;

    @Inject
    public RadioController(RadioService service, ValidationService validationService, FileUploadService fileUploadService) {
        this.service = service;
        this.validationService = validationService;
        this.fileUploadService = fileUploadService;
    }

    @Inject
    private GeolocationService geoService;

    public void setupRoutes(Router router) {
        String path = "/:brand/radio";

        BodyHandler jsonBodyHandler = BodyHandler.create()
                .setHandleFileUploads(false)
                .setBodyLimit(BODY_HANDLER_LIMIT);

        router.route(HttpMethod.GET, path + "/stream.m3u8").handler(this::getPlaylist);
        router.route(HttpMethod.GET, path + "/stream").handler(this::getSegment);
        router.route(HttpMethod.GET, path + "/segments/:segment").handler(this::getSegment);
        router.route(HttpMethod.GET, path + "/status").handler(this::getStatus);
        router.route(HttpMethod.PUT, path + "/wakeup").handler(this::wakeUp);

        router.route(HttpMethod.GET, "/radio/stations").handler(this::validateMixplaAccess).handler(this::getStations);
        router.route(HttpMethod.GET, "/radio/all-stations").handler(this::validateMixplaAccess).handler(this::getAllStations);
        router.route(HttpMethod.GET, "/radio/all-stations/:brand").handler(this::validateMixplaAccess).handler(this::getStation);
        router.route(HttpMethod.POST, "/radio/alexa/skill").handler(this::getSkill);

         router.route(HttpMethod.POST, "/radio/:brand/submissions")
                .handler(jsonBodyHandler)
                .handler(this::validateMixplaAccess)
                .handler(this::submit);
        router.route(HttpMethod.POST, "/radio/:brand/messages")
                .handler(jsonBodyHandler)
                .handler(this::validateMixplaAccess)
                .handler(this::postMessage);

        router.route(HttpMethod.POST, "/radio/:brand/submissions/files/:id").handler(this::uploadFile);
        router.route(HttpMethod.OPTIONS, "/radio/:brand/submissions/files/:id").handler(rc -> rc.response().setStatusCode(204).end());
    }

    private void getPlaylist(RoutingContext rc) {
        String brand = rc.pathParam("brand").toLowerCase();
        String userAgent = rc.request().getHeader("User-Agent");
        String clientIP = rc.request().getHeader("stream-connecting-ip");
        geoService.recordAccessWithGeolocation(brand, userAgent, clientIP)
                .chain(country -> service.getPlaylist(brand))
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

        service.getPlaylist(brand)
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

        service.getStatus(brand)
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

    private void getStation(RoutingContext rc) {
        service.getStation(rc.pathParam("brand"))
                .subscribe().with(
                        stations -> {
                            rc.response()
                                    .putHeader("Content-Type", MediaType.APPLICATION_JSON)
                                    .setStatusCode(200)
                                    .end(Json.encode(stations));
                        },
                        throwable -> {
                            LOGGER.error("Error getting  station: {}", throwable.getMessage());
                            rc.response()
                                    .setStatusCode(500)
                                    .putHeader("Content-Type", MediaType.TEXT_PLAIN)
                                    .end("Failed to get all stations: " + throwable.getMessage());
                        }
                );
    }

    private void submit(RoutingContext rc) {
        try {
            if (jsonBodyIsBad(rc)) {
                return;
            }

            SubmissionDTO dto = rc.body().asJsonObject().mapTo(SubmissionDTO.class);
            String[] ipCountryParts = GeolocationService.parseIPHeader(rc.request().getHeader("stream-connecting-ip"));
            dto.setIpAddress(ipCountryParts[0]);
            dto.setCountry(CountryCode.valueOf(ipCountryParts[1]));
            dto.setUserAgent(rc.request().getHeader("User-Agent"));
            String brand = rc.pathParam("brand");

            validationService.validateSubmissionDTO(dto)
                    .chain(validationResult -> {
                        if (!validationResult.valid()) {
                            return Uni.createFrom().failure(new IllegalArgumentException(validationResult.errorMessage()));
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

    private void postMessage(RoutingContext rc) {
        try {
            if (jsonBodyIsBad(rc)) {
                return;
            }

            MessageDTO dto = rc.body().asJsonObject().mapTo(MessageDTO.class);
            String[] ipCountryParts = GeolocationService.parseIPHeader(rc.request().getHeader("stream-connecting-ip"));
            dto.setIpAddress(ipCountryParts[0]);
            dto.setCountry(CountryCode.valueOf(ipCountryParts[1]));
            dto.setUserAgent(rc.request().getHeader("User-Agent"));
            String brand = rc.pathParam("brand");

            validationService.validateMessageDTO(dto)
                    .chain(validationResult -> {
                        if (!validationResult.valid()) {
                            return Uni.createFrom().failure(new IllegalArgumentException(validationResult.errorMessage()));
                        }
                        return service.postMessage(brand, dto);
                    })
                    .subscribe().with(
                            doc -> rc.response().setStatusCode(200).end(doc.toString()),
                            throwable -> {
                                if (throwable instanceof IllegalArgumentException) {
                                    rc.fail(400, throwable);
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
        String host = rc.request().remoteAddress().host();
        if ("127.0.0.1".equals(host) || "::1".equals(host)) {
            LOGGER.debug("Localhost Mixpla access from {}", host);
            rc.next();
            return;
        }

        String referer = rc.request().getHeader("Referer");
        String clientId = rc.request().getHeader("X-Client-ID");
        String mixplaApp = rc.request().getHeader("X-Mixpla-App");

        if (mixplaApp != null && isValidMixplaApp(mixplaApp)) {
            rc.next();
            return;
        }

        if (referer != null && isValidRefererHost(referer) && "mixpla-web".equals(clientId)) {
            rc.next();
            return;
        }

        LOGGER.warn("Invalid Mixpla access from host: {}", host);
        rc.response()
                .setStatusCode(403)
                .putHeader("Content-Type", MediaType.TEXT_PLAIN)
                .end("Access denied");
    }


    private boolean isValidRefererHost(String referer) {
        try {
            java.net.URL url = new java.net.URL(referer);
            String host = url.getHost().toLowerCase();
            return "mixpla.io".equals(host) || "localhost".equals(host);
        } catch (java.net.MalformedURLException e) {
            return false;
        }
    }

    private boolean isValidMixplaApp(String mixplaApp) {
        if (!mixplaApp.startsWith("mixpla-mobile")) { //mixpla-mobile
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

    protected boolean jsonBodyIsBad(RoutingContext rc) {
        JsonObject json = rc.body().asJsonObject();
        if (json == null) {
            rc.response().setStatusCode(400).end("Request body must be a valid JSON object");
            return true;
        } else {
            return false;
        }
    }

    private void uploadFile(RoutingContext rc) {
        String uploadId = rc.request().getParam("uploadId");

        try {
            fileUploadService.processDirectStream(rc, uploadId, "radio-controller", AnonymousUser.build())
                    .subscribe().with(
                            dto -> {
                                LOGGER.info("Upload done: {}", uploadId);
                                rc.response()
                                        .setStatusCode(200)
                                        .putHeader("Content-Type", "application/json")
                                        .end(io.vertx.core.json.Json.encode(dto));
                            },
                            err -> {
                                LOGGER.error("Upload failed: {}", uploadId, err);
                                if (err instanceof IllegalArgumentException e) {
                                    int status = e.getMessage() != null && e.getMessage().contains("Unsupported") ? 415 : 400;
                                    rc.response().setStatusCode(status).end(e.getMessage());
                                } else {
                                    rc.response().setStatusCode(500).end("Upload failed");
                                }
                            }
                    );
        } catch (IllegalArgumentException e) {
            int statusCode = e.getMessage() != null && e.getMessage().contains("Unsupported") ? 415 : 400;
            rc.fail(statusCode, e);
        } catch (Exception e) {
            rc.fail(e);
        }
    }


    private void getSkill(RoutingContext rc) {
        JsonObject response = new JsonObject()
                .put("version", "1.0")
                .put("sessionAttributes", new JsonObject())
                .put("response", new JsonObject()
                        .put("outputSpeech", new JsonObject()
                                .put("type", "PlainText")
                                .put("text", "Playing Mixpla Radio"))
                        .put("shouldEndSession", true)
                        .put("directives", List.of(
                                new JsonObject()
                                        .put("type", "AudioPlayer.Play")
                                        .put("playBehavior", "REPLACE_ALL")
                                        .put("audioItem", new JsonObject()
                                                .put("stream", new JsonObject()
                                                        .put("token", "aye-ayes-ear-001")
                                                        .put("url", "https://mixpla.online/aye-ayes-ear/radio/stream.m3u8")
                                                        .put("offsetInMilliseconds", 0)
                                                )
                                        )
                        ))
                );

        rc.response()
                .putHeader("Content-Type", "application/json")
                .setStatusCode(200)
                .end(response.encode());
    }



}