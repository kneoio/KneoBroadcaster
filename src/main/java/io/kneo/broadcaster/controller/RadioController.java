package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.dto.radio.SubmissionDTO;
import io.kneo.broadcaster.dto.radiostation.OneTimeStreamRunReqDTO;
import io.kneo.broadcaster.model.cnst.RatingAction;
import io.kneo.broadcaster.model.cnst.StreamStatus;
import io.kneo.broadcaster.service.OneTimeStreamService;
import io.kneo.broadcaster.service.RadioService;
import io.kneo.broadcaster.service.ScriptService;
import io.kneo.broadcaster.service.chat.PublicChatService;
import io.kneo.broadcaster.service.exceptions.RadioStationException;
import io.kneo.broadcaster.service.external.MailService;
import io.kneo.broadcaster.service.stream.HlsSegment;
import io.kneo.broadcaster.service.stream.IStreamManager;
import io.kneo.broadcaster.service.stream.Mp3Streamer;
import io.kneo.broadcaster.service.util.FileUploadService;
import io.kneo.broadcaster.service.util.GeolocationService;
import io.kneo.broadcaster.service.util.ValidationService;
import io.kneo.core.model.user.AnonymousUser;
import io.kneo.core.model.user.IUser;
import io.kneo.core.model.user.SuperUser;
import io.kneo.core.repository.exception.DocumentModificationAccessException;
import io.kneo.core.repository.exception.UploadAbsenceException;
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
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@ApplicationScoped
public class RadioController {
    private static final Logger LOGGER = LoggerFactory.getLogger(RadioController.class);
    private final RadioService service;
    private static final String[] SUPPORTED_MIXPLA_VERSIONS = {"2.5.5","2.5.6","2.5.7","2.5.8","2.5.9"};
    private final ValidationService validationService;
    private final FileUploadService fileUploadService;
    private static final long BODY_HANDLER_LIMIT = 1024L * 1024L * 1024L;

    @Inject
    public RadioController(RadioService service, ValidationService validationService, FileUploadService fileUploadService) {
        this.service = service;
        this.validationService = validationService;
        this.fileUploadService = fileUploadService;
    }

    @Inject GeolocationService geoService;
    @Inject
    Mp3Streamer mp3Streamer;
    @Inject
    ScriptService scriptService;
    @Inject
    OneTimeStreamService oneTimeStreamService;
    @Inject
    Validator validator;
    @Inject
    BroadcasterConfig broadcasterConfig;
    @Inject
    MailService mailService;
    @Inject
    PublicChatService publicChatService;

    public void setupRoutes(Router router) {
        String path = "/:brand/radio";

        BodyHandler jsonBodyHandler = BodyHandler.create()
                .setHandleFileUploads(false)
                .setBodyLimit(BODY_HANDLER_LIMIT);

        router.route(HttpMethod.GET, path + "/master.m3u8").handler(this::getMasterPlaylist);
        router.route(HttpMethod.GET, path + "/stream.m3u8").handler(this::getPlaylist);
        router.route(HttpMethod.GET, path + "/segments/:segment").handler(this::getSegment);
        router.route(HttpMethod.GET, path + "/status").handler(this::getStatus);   //used by Mixpla
        router.route(HttpMethod.GET, path + "/stream.mp3").handler(this::getMp3Stream);

        router.route(HttpMethod.GET, "/radio/stations").handler(this::validateMixplaAccess).handler(this::getStations);
        router.route(HttpMethod.GET, "/radio/all-stations").handler(this::validateMixplaAccess).handler(this::getAllStations);
        router.route(HttpMethod.GET, "/radio/all-stations/:brand").handler(this::validateMixplaAccess).handler(this::getStation);  //used by Web
        router.route(HttpMethod.GET, "/radio/shared-scripts").handler(this::validateMixplaAccess).handler(this::getSharedScripts);
        router.route(HttpMethod.POST, "/radio/run-stream").handler(jsonBodyHandler).handler(this::validateMixplaAccess).handler(this::runOneTimeStream);
        router.route(HttpMethod.PATCH, "/radio/:brand/:id/rating").handler(jsonBodyHandler).handler(this::validateMixplaAccess).handler(this::rateFragment);
        router.route(HttpMethod.POST, "/radio/alexa/skill").handler(jsonBodyHandler).handler(this::getSkill);


        router.route(HttpMethod.POST, "/radio/:brand/submissions")
                .handler(jsonBodyHandler)
                .handler(this::authenticateForSubmission)
                .handler(this::submit);

        router.route(HttpMethod.POST, "/radio/:brand/submissions/files/:id").handler(this::uploadFile);
        router.route(HttpMethod.OPTIONS, "/radio/:brand/submissions/files/:id").handler(rc -> rc.response().setStatusCode(204).end());
    }

    private void rateFragment(RoutingContext rc) {
        if (jsonBodyIsBad(rc)) return;

        try {
            String brand = rc.pathParam("brand");
            String id = rc.pathParam("id");
            JsonObject body = rc.body().asJsonObject();
            String actionStr = body.getString("action");


            RatingAction action;
            try {
                action = RatingAction.valueOf(actionStr);
            } catch (IllegalArgumentException e) {
                rc.fail(400, new IllegalArgumentException("Invalid action. Use LIKE or DISLIKE"));
                return;
            }

            service.getStreamManager(brand)
                    .map(manager -> {
                        HlsSegment segment;
                        // Try to parse as sequence number first (clean path)
                        try {
                            long sequence = Long.parseLong(id);
                            segment = manager.getSegment(sequence);
                        } catch (NumberFormatException e) {
                            // Fallback to string lookup (filename or regex path)
                            segment = manager.getSegment(id);
                        }

                        if (segment == null || segment.getLiveSoundFragment() == null || segment.getLiveSoundFragment().getSoundFragmentId() == null) {
                            throw new WebApplicationException("Segment not found or invalid", 404);
                        }
                        return segment.getLiveSoundFragment().getSoundFragmentId();
                    })
                    .chain(fragmentId -> {
                        String previousAction = body.getString("previousAction");
                        return service.rateSoundFragmentByAction(brand, fragmentId, action, previousAction);
                    })
                    .subscribe().with(
                            updated -> {
                                JsonObject response = new JsonObject();
                                response.put("updated", updated);
                                rc.response().setStatusCode(200).end(response.encode());
                            },
                            throwable -> {
                                if (throwable instanceof WebApplicationException webAppException) {
                                    rc.response().setStatusCode(webAppException.getResponse().getStatus()).end(webAppException.getMessage());
                                } else if (throwable instanceof IllegalArgumentException) {
                                    rc.fail(400, throwable);
                                } else {
                                    rc.fail(500, throwable);
                                }
                            }
                    );

        } catch (Exception e) {
            rc.fail(400, new IllegalArgumentException("Invalid JSON payload"));
        }
    }

    private void getMp3Stream(RoutingContext rc) {
        String brand = rc.pathParam("brand").toLowerCase();

        service.getStreamManager(brand)
                .subscribe().with(
                        streamManager -> {
                            rc.response()
                                    .putHeader("Content-Type", "audio/mpeg")
                                    .putHeader("Cache-Control", "no-cache")
                                    .putHeader("Connection", "keep-alive")
                                    .putHeader("Accept-Ranges", "none")
                                    .putHeader("Access-Control-Allow-Origin", "*")
                                    .putHeader("Content-Disposition", "inline")
                                    .putHeader("icy-br", "128")
                                    .putHeader("icy-pub", "1")
                                    .putHeader("icy-name", brand.toUpperCase() + " Radio")
                                    .putHeader("icy-genre", "Various")
                                    .putHeader("icy-url", "https://mixpla.online")
                                    .putHeader("icy-notice1", "<BR>Powered by Mixpla<BR>")
                                    .putHeader("icy-notice2", "Mixpla Radio Streaming<BR>")
                                    .setChunked(true);

                            rc.response().closeHandler(v -> {
                                LOGGER.info("Client disconnected from brand {}", brand);
                                mp3Streamer.listenerLeft(brand);
                            });

                            mp3Streamer.listenerJoined(brand);

                            mp3Streamer.stream(streamManager.getPlaylistManager())
                                    .subscribe().with(
                                            chunk -> {
                                                if (!rc.response().closed()) {
                                                    rc.response().write(chunk);
                                                }
                                            },
                                            err -> {
                                                if (!rc.response().closed()) {
                                                    rc.response().setStatusCode(500).end("Stream error");
                                                }
                                            },
                                            () -> {
                                                if (!rc.response().closed()) {
                                                    rc.response().end();
                                                }
                                            }
                                    );
                        },
                        throwable -> rc.response()
                                .setStatusCode(404)
                                .putHeader("Content-Type", MediaType.TEXT_PLAIN)
                                .end("Stream unavailable")
                );
    }

    private void getMasterPlaylist(RoutingContext rc) {
        String brand = rc.pathParam("brand").toLowerCase();
        service.getStreamManager(brand)
                .onItem().transform(IStreamManager::generateMasterPlaylist)
                .subscribe().with(
                        content -> rc.response()
                                .putHeader("Content-Type", "application/vnd.apple.mpegurl")
                                .putHeader("Cache-Control", "no-cache")
                                .end(content),
                        throwable -> {
                            if (throwable instanceof RadioStationException) {
                                rc.response().setStatusCode(404).end(throwable.getMessage());
                            } else {
                                rc.fail(throwable);
                            }
                        }
                );
    }

    private void getPlaylist(RoutingContext rc) {
        String brand = rc.pathParam("brand").toLowerCase();
        String userAgent = rc.request().getHeader("User-Agent");
        String clientIP = rc.request().getHeader("stream-connecting-ip");

        geoService.recordAccessWithGeolocation(brand, userAgent, clientIP)
                .chain(country -> service.getStreamManager(brand)
                        .onFailure(RadioStationException.class).recoverWithUni(ex -> {
                            LOGGER.info("Stream {} not in pool, checking if it's a OneTimeStream that needs lazy start", brand);
                            return oneTimeStreamService.getBySlugName(brand)
                                    .chain(oneTimeStream -> {
                                        if (oneTimeStream == null) {
                                            LOGGER.warn("Stream {} not found in OneTimeStreamService either", brand);
                                            return Uni.createFrom().failure(ex);
                                        }
                                        if (oneTimeStream.getStatus() == StreamStatus.OFF_LINE || oneTimeStream.getStatus() == StreamStatus.FINISHED) {
                                            LOGGER.info("OneTimeStream {} is {} (was stopped/finished), not restarting", brand, oneTimeStream.getStatus());
                                            return Uni.createFrom().failure(ex);
                                        }
                                        if (oneTimeStream.getStatus() == StreamStatus.PENDING) {
                                            LOGGER.info("Found PENDING OneTimeStream {}, starting it now for first listener", brand);
                                            return oneTimeStreamService.start(oneTimeStream)
                                                    .chain(started -> service.getStreamManager(brand));
                                        }
                                        LOGGER.warn("OneTimeStream {} has unexpected status {}, not starting", brand, oneTimeStream.getStatus());
                                        return Uni.createFrom().failure(ex);
                                    });
                        })
                )
                .onItem().transform(manager -> manager.generatePlaylist(rc.request().getParam("bitrate")))
                .subscribe().with(
                        playlistContent -> {
                            rc.response()
                                    .putHeader("Content-Type", "application/vnd.apple.mpegurl")
                                    .putHeader("Cache-Control", "no-cache")
                                    .end(playlistContent);
                        },
                        throwable -> {
                            if (throwable instanceof RadioStationException) {
                                rc.response().setStatusCode(404).end(throwable.getMessage());
                            } else {
                                rc.fail(throwable);
                            }
                        }
                );
    }

    private void getSegment(RoutingContext rc) {
        String segmentParam = rc.pathParam("segment");
        String brand = rc.pathParam("brand").toLowerCase();

        service.getStreamManager(brand)
                .onItem().transform(playlist -> {
                    HlsSegment segment = playlist.getSegment(segmentParam);
                    if (segment == null) {
                        throw new WebApplicationException(Response.Status.NOT_FOUND);
                    }
                    return segment.getData();
                })
                .subscribe().with(
                        data -> rc.response()
                                .putHeader("Content-Type", "video/MP2T")
                                .putHeader("Cache-Control", "no-cache")
                                .end(Buffer.buffer(data)),
                        throwable -> {
                            if (throwable instanceof WebApplicationException e) {
                                rc.response().setStatusCode(e.getResponse().getStatus()).end("Segment not found");
                            } else {
                                rc.response().setStatusCode(500).end("Error serving segment");
                            }
                        }
                );
    }

    private void getStatus(RoutingContext rc) {
        String brand = rc.pathParam("brand").toLowerCase();

        service.getStatus(brand)
                .subscribe().with(
                        statusDto -> rc.response()
                                .putHeader("Content-Type", MediaType.APPLICATION_JSON)
                                .end(Json.encode(statusDto)),
                        throwable -> {
                            if (throwable instanceof RadioStationException) {
                                rc.response().setStatusCode(404).end(throwable.getMessage());
                            } else {
                                rc.response().setStatusCode(500).end("Internal server error");
                            }
                        }
                );
    }


    private void getStations(RoutingContext rc) {
        service.getStations()
                .subscribe().with(
                        stations -> rc.response()
                                .putHeader("Content-Type", MediaType.APPLICATION_JSON)
                                .end(Json.encode(stations)),
                        throwable -> rc.response().setStatusCode(500).end("Failed to get stations list")
                );
    }

    private void getAllStations(RoutingContext rc) {
        String onlineParam = rc.request().getParam("online");
        Boolean onlineOnly = onlineParam != null ? Boolean.parseBoolean(onlineParam) : null;
        service.getAllStations(onlineOnly)
                .subscribe().with(
                        stations -> rc.response()
                                .putHeader("Content-Type", MediaType.APPLICATION_JSON)
                                .end(Json.encode(stations)),
                        throwable -> rc.response().setStatusCode(500).end("Failed to get all stations")
                );
    }

    private void getStation(RoutingContext rc) {
        service.getStation(rc.pathParam("brand"))
                .subscribe().with(
                        station -> rc.response()
                                .putHeader("Content-Type", MediaType.APPLICATION_JSON)
                                .end(Json.encode(station)),
                        throwable -> rc.response().setStatusCode(500).end("Failed to get station")
                );
    }

    private void getSharedScripts(RoutingContext rc) {
        scriptService.getAllShared(50, 0, AnonymousUser.build())
                .subscribe().with(
                        scripts -> rc.response()
                                .putHeader("Content-Type", MediaType.APPLICATION_JSON)
                                .end(Json.encode(scripts)),
                        throwable -> rc.response().setStatusCode(500).end("Failed to get shared scripts")
                );
    }

    private void runOneTimeStream(RoutingContext rc) {
        if (jsonBodyIsBad(rc)) {
            return;
        }

        try {
            OneTimeStreamRunReqDTO dto = rc.body().asJsonObject().mapTo(OneTimeStreamRunReqDTO.class);

            boolean startImmediately = dto.isStartImmediately();
            
            oneTimeStreamService.populateFromSlugName(dto, SuperUser.build())
                    .chain(populatedDto -> {
                        Set<ConstraintViolation<OneTimeStreamRunReqDTO>> violations = validator.validate(populatedDto);
                        if (violations != null && !violations.isEmpty()) {
                            Map<String, List<String>> fieldErrors = new HashMap<>();
                            for (ConstraintViolation<OneTimeStreamRunReqDTO> v : violations) {
                                String field = v.getPropertyPath().toString();
                                fieldErrors.computeIfAbsent(field, k -> new ArrayList<>()).add(v.getMessage());
                            }

                            String detail = fieldErrors.entrySet().stream()
                                    .flatMap(e -> e.getValue().stream().map(msg -> e.getKey() + ": " + msg))
                                    .collect(Collectors.joining(", "));

                            rc.response()
                                    .setStatusCode(400)
                                    .putHeader("Content-Type", MediaType.APPLICATION_JSON)
                                    .end(new JsonObject()
                                            .put("error", "Validation failed")
                                            .put("detail", detail)
                                            .encode());
                            return Uni.createFrom().nullItem();
                        }

                        return validationService.validateOneTimeStreamRunReqDTO(populatedDto)
                                .chain(validationResult -> {
                                    if (!validationResult.valid()) {
                                        rc.response()
                                                .setStatusCode(400)
                                                .putHeader("Content-Type", MediaType.APPLICATION_JSON)
                                                .end(new JsonObject()
                                                        .put("error", "Validation failed")
                                                        .put("detail", validationResult.errorMessage())
                                                        .encode());
                                        return Uni.createFrom().nullItem();
                                    }
                                    return oneTimeStreamService.run(populatedDto, SuperUser.build());
                                });
                    })
                    .subscribe().with(
                            stream -> {
                                if (stream != null) {
                                    String slugName = stream.getSlugName();
                                    String hlsUrl = broadcasterConfig.getHost() + "/" + slugName + "/radio/stream.m3u8";
                                    String mixplaUrl = "https://player.mixpla.io/?radio=" + slugName;
                                    
                                    if (dto.getEmail() != null && !dto.getEmail().isEmpty()) {
                                        mailService.sendStreamLinksAsync(dto.getEmail(), slugName, hlsUrl, mixplaUrl, dto.getSchedule())
                                                .subscribe().with(
                                                        v -> LOGGER.info("Stream links sent to email: {}", dto.getEmail()),
                                                        err -> LOGGER.error("Failed to send email to: {}", dto.getEmail(), err)
                                                );
                                    }
                                    
                                    String message = startImmediately 
                                            ? "Stream started successfully" 
                                            : "Stream created successfully. It will start when first listener connects.";
                                    
                                    JsonObject response = new JsonObject()
                                            .put("message", message)
                                            .put("slugName", slugName)
                                            .put("hlsUrl", hlsUrl)
                                            .put("mixplaUrl", mixplaUrl);
                                    rc.response()
                                            .putHeader("Content-Type", MediaType.APPLICATION_JSON)
                                            .setStatusCode(200)
                                            .end(response.encode());
                                }
                            },
                            throwable -> {
                                LOGGER.error("Failed to run one-time stream", throwable);
                                if (throwable instanceof IllegalArgumentException) {
                                    rc.response().setStatusCode(400).end(throwable.getMessage());
                                } else {
                                    rc.response().setStatusCode(500).end("Failed to run stream");
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

    private void authenticateForSubmission(RoutingContext rc) {
        // Try token authentication first (from chat system)
        String token = rc.request().getHeader("Authorization");
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7);
        } else {
            token = rc.request().getParam("token");
        }
        
        if (token != null && !token.isBlank()) {
            publicChatService.authenticateUserFromToken(token)
                    .subscribe().with(
                            user -> {
                                rc.put("user", user);
                                rc.next();
                            },
                            err -> {
                                // Token auth failed, try Mixpla access
                                validateMixplaAccess(rc);
                            }
                    );
        } else {
            // No token, try Mixpla access
            validateMixplaAccess(rc);
        }
    }

    private void submit(RoutingContext rc) {
        if (jsonBodyIsBad(rc)) return;

        try {
            SubmissionDTO dto = rc.body().asJsonObject().mapTo(SubmissionDTO.class);
            String brand = rc.pathParam("brand");
            String ipHeader = rc.request().getHeader("stream-connecting-ip");
            String userAgent = rc.request().getHeader("User-Agent");
            
            // Check if user is authenticated via token
            IUser user = rc.get("user");
            boolean isTokenAuth = user != null;

            validationService.validateSubmissionDTO(dto)
                    .chain(v -> v.valid()
                            ? service.submit(brand, dto, ipHeader, userAgent, user)
                            : Uni.createFrom().failure(new IllegalArgumentException(v.errorMessage())))
                    .subscribe().with(
                            ok -> rc.response().setStatusCode(200).end(ok.toString()),
                            throwable -> {
                                if (throwable instanceof IllegalArgumentException) rc.fail(400, throwable);
                                else if (throwable instanceof DocumentModificationAccessException)
                                    rc.response().setStatusCode(403).end("Not enough rights");
                                else if (throwable instanceof UploadAbsenceException)
                                    rc.response().setStatusCode(400).end(throwable.getMessage());
                                else rc.fail(throwable);
                            }
                    );

        } catch (Exception e) {
            rc.fail(400, new IllegalArgumentException("Invalid JSON payload"));
        }
    }

    private void validateMixplaAccess(RoutingContext rc) {
        String host = rc.request().remoteAddress().host();
        if ("127.0.0.1".equals(host) || "::1".equals(host)) { rc.next(); return; }

        String clientId = rc.request().getHeader("X-Client-ID");
        String mixplaApp = rc.request().getHeader("X-Mixpla-App");

        if (mixplaApp != null && isValidMixplaApp(mixplaApp)) { rc.next(); return; }
        if ("mixpla-web".equals(clientId)) { rc.next(); return; }

        rc.response().setStatusCode(403).end("Access denied");
    }

    private boolean isValidMixplaApp(String mixplaApp) {
        final String prefix = "mixpla-mobile";
        if (!mixplaApp.startsWith(prefix)) return false;

        String version = mixplaApp.substring(prefix.length()).replaceFirst("^[^0-9]*", "");
        for (String v : SUPPORTED_MIXPLA_VERSIONS) if (v.equals(version)) return true;
        return false;
    }

    protected boolean jsonBodyIsBad(RoutingContext rc) {
        JsonObject json = rc.body().asJsonObject();
        if (json == null) {
            rc.response().setStatusCode(400).end("Request body must be JSON");
            return true;
        }
        return false;
    }

    private void uploadFile(RoutingContext rc) {
        String uploadId = rc.request().getParam("uploadId");

        try {
            fileUploadService.processDirectStream(rc, uploadId, "radio-controller", AnonymousUser.build())
                    .subscribe().with(
                            dto -> rc.response().setStatusCode(200).putHeader("Content-Type", "application/json").end(Json.encode(dto)),
                            err -> {
                                if (err instanceof IllegalArgumentException e) {
                                    int status = e.getMessage().contains("Unsupported") ? 415 : 400;
                                    rc.response().setStatusCode(status).end(e.getMessage());
                                } else rc.response().setStatusCode(500).end("Upload failed");
                            }
                    );
        } catch (Exception e) {
            rc.fail(e);
        }
    }

    private void getSkill(RoutingContext rc) {
        JsonObject requestJson = rc.body().asJsonObject();
        String brand = "sacana";

        try {
            if (requestJson.containsKey("request")) {
                JsonObject request = requestJson.getJsonObject("request");
                if ("IntentRequest".equals(request.getString("type"))) {
                    JsonObject intent = request.getJsonObject("intent");
                    if ("PlayRadioIntent".equals(intent.getString("name"))) {
                        JsonObject slots = intent.getJsonObject("slots");
                        if (slots != null && slots.containsKey("brand")) {
                            String requestedBrand = slots.getJsonObject("brand").getString("value");
                            if (requestedBrand != null && !requestedBrand.isEmpty()) {
                                brand = requestedBrand.toLowerCase();
                            }
                        }
                    }
                }
            }

            String streamUrl = "https://mixpla.online/" + brand + "/radio/stream.mp3";
            String speechText = "Starting " + brand + " radio. Enjoy!";

            JsonObject response = new JsonObject()
                    .put("version", "1.0")
                    .put("sessionAttributes", new JsonObject())
                    .put("response", new JsonObject()
                            .put("outputSpeech", new JsonObject()
                                    .put("type", "PlainText")
                                    .put("text", speechText))
                            .put("shouldEndSession", true)
                            .put("directives", List.of(
                                    new JsonObject()
                                            .put("type", "AudioPlayer.ClearQueue")
                                            .put("clearBehavior", "CLEAR_ALL"),
                                    new JsonObject()
                                            .put("type", "AudioPlayer.Play")
                                            .put("playBehavior", "REPLACE_ALL")
                                            .put("audioItem", new JsonObject()
                                                    .put("stream", new JsonObject()
                                                            .put("token", brand + "-radio-stream")
                                                            .put("url", streamUrl)
                                                            .put("offsetInMilliseconds", 0)
                                                    )
                                            )
                            )));

            rc.response().putHeader("Content-Type", "application/json").end(response.encode());

        } catch (Exception e) {
            JsonObject errorResponse = new JsonObject()
                    .put("version", "1.0")
                    .put("response", new JsonObject()
                            .put("outputSpeech", new JsonObject()
                                    .put("type", "PlainText")
                                    .put("text", "Sorry, I had trouble finding that station."))
                            .put("shouldEndSession", true));
            rc.response()
                    .putHeader("Content-Type", "application/json")
                    .end(errorResponse.encode());
        }
    }
}
