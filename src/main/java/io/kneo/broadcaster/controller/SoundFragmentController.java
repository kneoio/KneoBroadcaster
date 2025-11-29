package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.dto.BrandSoundFragmentDTO;
import io.kneo.broadcaster.dto.BulkBrandUpdateDTO;
import io.kneo.broadcaster.dto.SoundFragmentDTO;
import io.kneo.broadcaster.dto.actions.SoundFragmentActionsFactory;
import io.kneo.broadcaster.dto.filter.SoundFragmentFilterDTO;
import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.model.cnst.SourceType;
import io.kneo.broadcaster.model.soundfragment.SoundFragment;
import io.kneo.broadcaster.service.FileDownloadService;
import io.kneo.broadcaster.service.FileUploadService;
import io.kneo.broadcaster.service.ValidationResult;
import io.kneo.broadcaster.service.ValidationService;
import io.kneo.broadcaster.service.soundfragment.SoundFragmentService;
import io.kneo.broadcaster.util.FileSecurityUtils;
import io.kneo.broadcaster.util.InputStreamReadStream;
import io.kneo.broadcaster.util.ProblemDetailsUtil;
import io.kneo.core.controller.AbstractSecuredController;
import io.kneo.core.dto.actions.ActionBox;
import io.kneo.core.dto.cnst.PayloadType;
import io.kneo.core.dto.form.FormPage;
import io.kneo.core.dto.view.View;
import io.kneo.core.dto.view.ViewPage;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.repository.exception.UserNotFoundException;
import io.kneo.core.service.UserService;
import io.kneo.core.util.RuntimeUtil;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.tuples.Tuple2;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class SoundFragmentController extends AbstractSecuredController<SoundFragment, SoundFragmentDTO> {
    private static final Logger LOGGER = LoggerFactory.getLogger(SoundFragmentController.class);
    private static final long BODY_HANDLER_LIMIT = 1024L * 1024L * 1024L;
    private static final int STREAM_BUFFER_SIZE = 524288; // 512KB buffer for file streaming

    private SoundFragmentService service;
    private FileUploadService fileUploadService;
    private FileDownloadService fileDownloadService;
    private ValidationService validationService;
    private Vertx vertx;

    public SoundFragmentController() {
        super(null);
    }

    @Inject
    public SoundFragmentController(UserService userService,
                                   SoundFragmentService service,
                                   FileUploadService fileUploadService,
                                   FileDownloadService fileDownloadService,
                                   ValidationService validationService,
                                   Vertx vertx) {
        super(userService);
        this.service = service;
        this.fileUploadService = fileUploadService;
        this.fileDownloadService = fileDownloadService;
        this.validationService = validationService;
        this.vertx = vertx;
    }

    public void setupRoutes(Router router) {
        String path = "/api/soundfragments";
        BodyHandler jsonBodyHandler = BodyHandler.create().setHandleFileUploads(false);
        router.route(HttpMethod.GET, path).handler(this::get);
        router.route(HttpMethod.GET, path + "/available-soundfragments").handler(this::getForBrand);
        router.route(HttpMethod.GET, path + "/available-soundfragments/:id").handler(this::getForBrand);
        router.route(HttpMethod.GET, path + "/search").handler(this::search);
        //router.route(HttpMethod.GET, path + "/upload-progress/:uploadId/stream").handler(this::streamProgress);
        router.route(HttpMethod.GET, path + "/:id").handler(this::getById);
        router.route(HttpMethod.GET, path + "/files/:id/:slug").handler(this::getBySlugName);
        router.route(HttpMethod.POST, path + "/bulk-brand-update").handler(jsonBodyHandler).handler(this::bulkBrandUpdate);
        router.route(HttpMethod.POST, path + "/:id?").handler(jsonBodyHandler).handler(this::upsert);
        router.route(HttpMethod.DELETE, path + "/:id").handler(this::delete);
       // router.route(HttpMethod.POST, path + "/files/:id/start").handler(jsonBodyHandler).handler(this::startUploadSession);  //or submissons
        router.route(HttpMethod.POST, path + "/files/:id").handler(this::uploadFile); //!!
        router.route(HttpMethod.GET, path + "/:id/access").handler(this::getDocumentAccess);

    }

    private void get(RoutingContext rc) {
        int page = Integer.parseInt(rc.request().getParam("page", "1"));
        int size = Integer.parseInt(rc.request().getParam("size", "10"));
        SoundFragmentFilterDTO filter = parseFilterDTO(rc);

        getContextUser(rc, false, true)
                .chain(user -> Uni.combine().all().unis(
                        service.getAllCount(user, filter),
                        service.getAllDTO(size, (page - 1) * size, user, filter)
                ).asTuple().map(tuple -> {
                    ViewPage viewPage = new ViewPage();
                    View<SoundFragmentDTO> dtoEntries = new View<>(tuple.getItem2(),
                            tuple.getItem1(), page,
                            RuntimeUtil.countMaxPage(tuple.getItem1(), size),
                            size);
                    viewPage.addPayload(PayloadType.VIEW_DATA, dtoEntries);
                    ActionBox actions = SoundFragmentActionsFactory.getViewActions(user.getActivatedRoles());
                    viewPage.addPayload(PayloadType.CONTEXT_ACTIONS, actions);
                    return viewPage;
                }))
                .subscribe().with(
                        viewPage -> rc.response().setStatusCode(200).end(JsonObject.mapFrom(viewPage).encode()),
                        t -> handleFailure(rc, t)
                );
    }

    private void getById(RoutingContext rc) {
        String id = rc.pathParam("id");
        LanguageCode languageCode = LanguageCode.valueOf(rc.request().getParam("lang", LanguageCode.en.name()));

        getContextUser(rc, false, true)
                .chain(user -> {
                    if ("new".equals(id)) {
                        return service.getDTOTemplate(user, languageCode)
                                .map(dto -> Tuple2.of(dto, user));
                    }
                    return service.getDTO(UUID.fromString(id), user, languageCode)
                            .map(doc -> Tuple2.of(doc, user));
                })
                .subscribe().with(
                        tuple -> {
                            SoundFragmentDTO doc = tuple.getItem1();
                            FormPage page = new FormPage();
                            page.addPayload(PayloadType.DOC_DATA, doc);
                            rc.response().setStatusCode(200).end(JsonObject.mapFrom(page).encode());
                        },
                        t -> handleFailure(rc, t)
                );
    }

    private void getForBrand(RoutingContext rc) {
        String brandName = rc.request().getParam("brand");
        int page = Integer.parseInt(rc.request().getParam("page", "1"));
        int size = Integer.parseInt(rc.request().getParam("size", "10"));
        SoundFragmentFilterDTO filter = parseFilterDTO(rc);

        getContextUser(rc, false, true)
                .chain(user -> Uni.combine().all().unis(
                        service.getBrandSoundFragments(brandName, size, (page - 1) * size, filter),
                        service.getBrandSoundFragmentsCount(brandName, filter)
                ).asTuple().map(tuple -> {
                    ViewPage viewPage = new ViewPage();
                    View<BrandSoundFragmentDTO> dtoEntries = new View<>(tuple.getItem1(),
                            tuple.getItem2(), page,
                            RuntimeUtil.countMaxPage(tuple.getItem2(), size),
                            size);
                    viewPage.addPayload(PayloadType.VIEW_DATA, dtoEntries);
                    return viewPage;
                }))
                .subscribe().with(
                        viewPage -> rc.response().setStatusCode(200).end(JsonObject.mapFrom(viewPage).encode()),
                        t -> handleFailure(rc, t)
                );
    }

    private void search(RoutingContext rc) {
        String searchTerm = rc.request().getParam("q");
        int page = Integer.parseInt(rc.request().getParam("page", "1"));
        int size = Integer.parseInt(rc.request().getParam("size", "10"));
        SoundFragmentFilterDTO filter = parseFilterDTO(rc);

        if (searchTerm == null || searchTerm.trim().isEmpty()) {
            rc.fail(400, new IllegalArgumentException("Search term 'q' parameter is required"));
            return;
        }

        getContextUser(rc, false, true)
                .chain(user -> Uni.combine().all().unis(
                        service.getSearchCount(searchTerm, user, filter),
                        service.search(searchTerm, size, (page - 1) * size, user, filter)
                ).asTuple().map(tuple -> {
                    ViewPage viewPage = new ViewPage();
                    View<SoundFragmentDTO> dtoEntries = new View<>(tuple.getItem2(),
                            tuple.getItem1(), page,
                            RuntimeUtil.countMaxPage(tuple.getItem1(), size),
                            size);
                    viewPage.addPayload(PayloadType.VIEW_DATA, dtoEntries);
                    ActionBox actions = SoundFragmentActionsFactory.getViewActions(user.getActivatedRoles());
                    viewPage.addPayload(PayloadType.CONTEXT_ACTIONS, actions);
                    return viewPage;
                }))
                .subscribe().with(
                        viewPage -> rc.response().setStatusCode(200).end(JsonObject.mapFrom(viewPage).encode()),
                        t -> handleFailure(rc, t)
                );
    }

    private void upsert(RoutingContext rc) {
        try {
            if (!validateJsonBody(rc)) {
                return;
            }

            SoundFragmentDTO dto = rc.body().asJsonObject().mapTo(SoundFragmentDTO.class);
            String id = rc.pathParam("id");

            ValidationResult validationResult = validationService.validateSoundFragmentDTO(id, dto);
            if (!validationResult.valid()) {
                ProblemDetailsUtil.respondValidationError(rc, validationResult.errorMessage(), validationResult.fieldErrors());
                return;
            }

            getContextUser(rc, false, true)
                    .chain(user -> service.upsert(id, dto, user, LanguageCode.en))
                    .subscribe().with(
                            doc -> sendUpsertResponse(rc, doc, id),
                            t -> handleFailure(rc, t)
                    );

        } catch (Exception e) {
            if (e instanceof IllegalArgumentException) {
                rc.fail(400, e);
            } else {
                rc.fail(400, new IllegalArgumentException("Invalid JSON payload"));
            }
        }
    }

    private void uploadFile(RoutingContext rc) {
        String uploadId = rc.request().getParam("uploadId");
        String entityId = rc.pathParam("id");

        getContextUser(rc, false, true)
                .chain(user -> fileUploadService.processDirectStream(rc, uploadId, "sound-fragments-controller", entityId, user, true))
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

    private void delete(RoutingContext rc) {
        String id = rc.pathParam("id");
        getContextUser(rc, false, true)
                .chain(user -> service.archive(id, user))
                .subscribe().with(
                        count -> rc.response().setStatusCode(count > 0 ? 204 : 404).end(),
                        t -> handleFailure(rc, t)
                );
    }

    private void getBySlugName(RoutingContext rc) {
        String id = rc.pathParam("id");
        String requestedFileName = rc.pathParam("slug");

        getContextUser(rc, false, true)
                .chain(user -> fileDownloadService.getFile(id, requestedFileName, user))
                .subscribe().with(
                        fileData -> {
                            if (fileData == null ||
                                    (fileData.getData() == null && fileData.getInputStream() == null) ||
                                    (fileData.hasByteArray() && fileData.getData().length == 0)) {
                                rc.fail(404, new IllegalArgumentException("File content not available"));
                                return;
                            }

                            HttpServerResponse response = rc.response()
                                    .putHeader("Content-Disposition", "attachment; filename=\"" +
                                            FileSecurityUtils.sanitizeFilename(requestedFileName) + "\"")
                                    .putHeader("Content-Type", fileData.getMimeType())
                                    .putHeader("Content-Length", String.valueOf(fileData.getContentLength()));

                            if (fileData.hasByteArray()) {
                                response.end(Buffer.buffer(fileData.getData()));
                            } else if (fileData.hasInputStream()) {
                                response.setChunked(true);

                                InputStreamReadStream inputStreamReadStream = new InputStreamReadStream(vertx, fileData.getInputStream(), STREAM_BUFFER_SIZE);
                                inputStreamReadStream.pipeTo(response)
                                        .onComplete(ar -> {
                                            if (ar.failed()) {
                                                LOGGER.error("Stream failed", ar.cause());
                                                if (!response.ended()) {
                                                    response.setStatusCode(500).end();
                                                }
                                            }
                                        });
                            }
                        },
                        t -> handleFailure(rc, t)
                );
    }

    private void bulkBrandUpdate(RoutingContext rc) {
        try {
            if (!validateJsonBody(rc)) {
                return;
            }

            BulkBrandUpdateDTO dto = rc.body().asJsonObject().mapTo(BulkBrandUpdateDTO.class);

            if (dto.getDocumentIds() == null || dto.getDocumentIds().isEmpty()) {
                rc.fail(400, new IllegalArgumentException("Document IDs are required"));
                return;
            }

            if (dto.getOperation() == null || (!dto.getOperation().equals("SET") && !dto.getOperation().equals("UNSET"))) {
                rc.fail(400, new IllegalArgumentException("Operation must be SET or UNSET"));
                return;
            }

            if ("SET".equals(dto.getOperation()) && (dto.getBrands() == null || dto.getBrands().isEmpty())) {
                rc.fail(400, new IllegalArgumentException("Brands list is required for SET operation"));
                return;
            }

            getContextUser(rc, false, true)
                    .chain(user -> service.bulkBrandUpdate(dto.getDocumentIds(), dto.getBrands(), dto.getOperation(), user))
                    .subscribe().with(
                            updatedCount -> {
                                JsonObject response = new JsonObject();
                                response.put("updatedCount", updatedCount);
                                response.put("operation", dto.getOperation());
                                response.put("brands", dto.getBrands());
                                rc.response()
                                        .setStatusCode(200)
                                        .putHeader("Content-Type", "application/json")
                                        .end(response.encode());
                            },
                            t -> handleFailure(rc, t)
                    );

        } catch (Exception e) {
            LOGGER.error("Error parsing bulk brand update request", e);
            rc.fail(400, new IllegalArgumentException("Invalid JSON payload"));
        }
    }

    private void getDocumentAccess(RoutingContext rc) {
        String id = rc.pathParam("id");

        try {
            UUID documentId = UUID.fromString(id);

            getContextUser(rc, false, true)
                    .chain(user -> service.getDocumentAccess(documentId, user))
                    .subscribe().with(
                            accessList -> {
                                JsonObject response = new JsonObject();
                                response.put("documentId", id);
                                response.put("accessList", accessList);
                                rc.response()
                                        .setStatusCode(200)
                                        .putHeader("Content-Type", "application/json")
                                        .end(response.encode());
                            },
                            t -> handleFailure(rc, t)
                    );
        } catch (IllegalArgumentException e) {
            rc.fail(400, new IllegalArgumentException("Invalid document ID format"));
        }
    }

    private SoundFragmentFilterDTO parseFilterDTO(RoutingContext rc) {
        String filterParam = rc.request().getParam("filter");
        if (filterParam == null || filterParam.trim().isEmpty()) {
            return null;
        }
        SoundFragmentFilterDTO dto = new SoundFragmentFilterDTO();
        boolean any = false;
        try {
            JsonObject json = new JsonObject(filterParam);
            JsonArray g = json.getJsonArray("genres");
            if (g != null && !g.isEmpty()) {
                List<UUID> genres = new ArrayList<>();
                for (Object o : g) {
                    if (o instanceof String s) {
                        try {
                            genres.add(UUID.fromString(s));
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                }
                if (!genres.isEmpty()) {
                    dto.setGenres(genres);
                    any = true;
                }
            }
            JsonArray s = json.getJsonArray("sources");
            if (s != null && !s.isEmpty()) {
                List<SourceType> sources = new ArrayList<>();
                for (Object o : s) {
                    if (o instanceof String str) {
                        try {
                            sources.add(SourceType.valueOf(str));
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                }
                if (!sources.isEmpty()) {
                    dto.setSources(sources);
                    any = true;
                }
            }
            JsonArray t = json.getJsonArray("types");
            if (t != null && !t.isEmpty()) {
                List<PlaylistItemType> types = new ArrayList<>();
                for (Object o : t) {
                    if (o instanceof String str) {
                        try {
                            types.add(PlaylistItemType.valueOf(str));
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                }
                if (!types.isEmpty()) {
                    dto.setTypes(types);
                    any = true;
                }
            }
            if (json.containsKey("activated")) {
                dto.setActivated(json.getBoolean("activated", false));
                any = true;
            } else if (json.containsKey("filterActivated")) {
                dto.setActivated(json.getBoolean("filterActivated", false));
                any = true;
            }
        } catch (Exception e) {
            return null;
        }
        return any ? dto : null;
    }

    protected void handleFailure(RoutingContext rc, Throwable throwable) {
        if (throwable instanceof IllegalStateException
                || throwable instanceof IllegalArgumentException
                || throwable instanceof UserNotFoundException) {
            rc.fail(401, throwable);
        } else {
            rc.fail(throwable); // default bubbling
        }
    }
}
