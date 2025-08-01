package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.dto.BrandSoundFragmentDTO;
import io.kneo.broadcaster.dto.SoundFragmentDTO;
import io.kneo.broadcaster.dto.filter.SoundFragmentFilterDTO;
import io.kneo.broadcaster.dto.UploadFileDTO;
import io.kneo.broadcaster.dto.actions.SoundFragmentActionsFactory;
import io.kneo.broadcaster.model.SoundFragment;
import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.model.cnst.SourceType;
import io.kneo.broadcaster.service.FileDownloadService;
import io.kneo.broadcaster.service.FileUploadService;
import io.kneo.broadcaster.service.SoundFragmentService;
import io.kneo.broadcaster.service.ValidationService;
import io.kneo.broadcaster.util.FileSecurityUtils;
import io.kneo.core.controller.AbstractSecuredController;
import io.kneo.core.dto.actions.ActionBox;
import io.kneo.core.dto.cnst.PayloadType;
import io.kneo.core.dto.form.FormPage;
import io.kneo.core.dto.view.View;
import io.kneo.core.dto.view.ViewPage;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.repository.exception.DocumentHasNotFoundException;
import io.kneo.core.service.UserService;
import io.kneo.core.util.RuntimeUtil;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.tuples.Tuple2;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.FileUpload;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class SoundFragmentController extends AbstractSecuredController<SoundFragment, SoundFragmentDTO> {
    private static final Logger LOGGER = LoggerFactory.getLogger(SoundFragmentController.class);
    private static final long BODY_HANDLER_LIMIT = 1024L * 1024L * 1024L;

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

        BodyHandler bodyHandler = BodyHandler.create()
                .setHandleFileUploads(true)
                .setMergeFormAttributes(true)
                .setDeleteUploadedFilesOnEnd(false)
                .setBodyLimit(BODY_HANDLER_LIMIT);

        BodyHandler jsonBodyHandler = BodyHandler.create().setHandleFileUploads(false);

        router.route(path + "*").handler(this::addHeaders);
        router.route(HttpMethod.GET, path).handler(this::get);
        router.route(HttpMethod.GET, path + "/available-soundfragments").handler(this::getForBrand);
        router.route(HttpMethod.GET, path + "/available-soundfragments/:id").handler(this::getForBrand);
        router.route(HttpMethod.GET, path + "/search").handler(this::search);
        router.route(HttpMethod.GET, path + "/upload-progress/:uploadId/stream").handler(this::streamProgress);
        router.route(HttpMethod.GET, path + "/:id").handler(this::getById);
        router.route(HttpMethod.GET, path + "/files/:id/:slug").handler(this::getBySlugName);
        router.route(HttpMethod.POST, path + "/:id?").handler(jsonBodyHandler).handler(this::upsert);
        router.route(HttpMethod.DELETE, path + "/:id").handler(this::delete);
        router.route(HttpMethod.POST, path + "/files/:id/start").handler(jsonBodyHandler).handler(this::startUploadSession);
        router.route(HttpMethod.POST, path + "/files/:id").handler(bodyHandler).handler(this::uploadFile);
        router.route(HttpMethod.GET, path + "/:id/access").handler(this::getDocumentAccess);
        router.route(HttpMethod.POST, path + "/bulk-brand-update").handler(jsonBodyHandler).handler(this::bulkBrandUpdate);
    }

    // DTO for bulk brand operation request
    public static class BulkBrandUpdateDTO {
        private List<UUID> documentIds;
        private List<String> brands;
        private String operation; // "SET" or "UNSET"

        public List<UUID> getDocumentIds() { return documentIds; }
        public void setDocumentIds(List<UUID> documentIds) { this.documentIds = documentIds; }
        public List<String> getBrands() { return brands; }
        public void setBrands(List<String> brands) { this.brands = brands; }
        public String getOperation() { return operation; }
        public void setOperation(String operation) { this.operation = operation; }
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
                        rc::fail
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
                        rc::fail
                );
    }

    private void getForBrand(RoutingContext rc) {
        String brandName = rc.request().getParam("brand");
        String format = rc.request().getParam("format");
        int page = Integer.parseInt(rc.request().getParam("page", "1"));
        int size = Integer.parseInt(rc.request().getParam("size", "10"));
        SoundFragmentFilterDTO filter = parseFilterDTO(rc);

        getContextUser(rc, false, true)
                .chain(user -> Uni.combine().all().unis(
                        service.getBrandSoundFragments(brandName, size, (page - 1) * size, filter),
                        service.getCountBrandSoundFragments(brandName, user, filter)
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
                        rc::fail
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
                        rc::fail
                );
    }

    private void upsert(RoutingContext rc) {
        try {
            if (!validateJsonBody(rc)) {
                return;
            }

            SoundFragmentDTO dto = rc.body().asJsonObject().mapTo(SoundFragmentDTO.class);
            String id = rc.pathParam("id");

            ValidationService.ValidationResult validationResult = validationService.validateSoundFragmentDTO(dto);
            if (!validationResult.isValid()) {
                rc.fail(400, new IllegalArgumentException(validationResult.getErrorMessage()));
                return;
            }

            getContextUser(rc, false, true)
                    .chain(user -> service.upsert(id, dto, user, LanguageCode.en))
                    .subscribe().with(
                            doc -> sendUpsertResponse(rc, doc, id),
                            throwable -> handleUpsertFailure(rc, throwable)
                    );

        } catch (Exception e) {
            if (e instanceof IllegalArgumentException) {
                rc.fail(400, e);
            } else {
                rc.fail(400, new IllegalArgumentException("Invalid JSON payload"));
            }
        }
    }

    private void delete(RoutingContext rc) {
        String id = rc.pathParam("id");
        getContextUser(rc, false, true)
                .chain(user -> service.archive(id, user))
                .subscribe().with(
                        count -> rc.response().setStatusCode(count > 0 ? 204 : 404).end(),
                        rc::fail
                );
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

        getContextUser(rc, false, true)
                .chain(user -> {
                    UploadFileDTO uploadDto = fileUploadService.createUploadSession(uploadId, clientStartTimeStr);
                    rc.response()
                            .putHeader("Content-Type", "application/json")
                            .end(JsonObject.mapFrom(uploadDto).encode());

                    return Uni.createFrom().voidItem();
                })
                .subscribe().with(
                        success -> {
                            LOGGER.info("Upload session started for uploadId: {}", uploadId);
                        },
                        error -> {
                            LOGGER.error("Error starting upload session for uploadId: {}", uploadId, error);
                            if (!rc.response().ended()) {
                                rc.fail(500, error);
                            }
                        }
                );
    }

    private void uploadFile(RoutingContext rc) {
        if (rc.fileUploads().isEmpty()) {
            rc.fail(400, new IllegalArgumentException("No file uploaded"));
            return;
        }

        String entityId = rc.pathParam("id");
        FileUpload uploadedFile = rc.fileUploads().get(0);
        String uploadId = rc.request().getParam("uploadId");

        if (uploadId == null || uploadId.trim().isEmpty()) {
            rc.fail(400, new IllegalArgumentException("uploadId parameter is required"));
            return;
        }

        try {
            fileUploadService.validateUpload(uploadedFile);
        } catch (IllegalArgumentException e) {
            int statusCode = e.getMessage().contains("too large") ? 413 : 415;
            rc.fail(statusCode, e);
            return;
        }

        getContextUser(rc, false, true)
                .chain(user -> {
                    rc.response().setStatusCode(202).end();
                    return fileUploadService.processFile(uploadedFile, uploadId, entityId, user, uploadedFile.fileName());
                })
                .subscribe().with(
                        success -> LOGGER.info("Upload done: {}", uploadId),
                        error -> LOGGER.error("Upload failed: {}", uploadId, error)
                );

    }

    private void streamProgress(RoutingContext rc) {
        String uploadId = rc.pathParam("uploadId");

        rc.response()
                .putHeader("Content-Type", "text/event-stream")
                .putHeader("Cache-Control", "no-cache")
                .setChunked(true);

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


    private void getBySlugName(RoutingContext rc) {
        String id = rc.pathParam("id");
        String requestedFileName = rc.pathParam("slug");

        getContextUser(rc, false, true)
                .chain(user -> fileDownloadService.getFile(id, requestedFileName, user))
                .subscribe().with(
                        fileData -> {
                            if (fileData == null || fileData.getData() == null || fileData.getData().length == 0) {
                                rc.fail(404, new IllegalArgumentException("File content not available"));
                                return;
                            }

                            rc.response()
                                    .putHeader("Content-Disposition", "attachment; filename=\"" +
                                            FileSecurityUtils.sanitizeFilename(requestedFileName) + "\"")
                                    .putHeader("Content-Type", fileData.getMimeType())
                                    .putHeader("Content-Length", String.valueOf(fileData.getData().length))
                                    .end(Buffer.buffer(fileData.getData()));
                        },
                        throwable -> {
                            if (throwable instanceof SecurityException) {
                                rc.fail(403, throwable);
                            } else if (throwable instanceof IllegalArgumentException) {
                                rc.fail(400, throwable);
                            } else if (throwable instanceof FileNotFoundException ||
                                    throwable instanceof DocumentHasNotFoundException) {
                                rc.fail(404, throwable);
                            } else {
                                rc.fail(500, throwable);
                            }
                        }
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
                            throwable -> {
                                LOGGER.error("Bulk brand update failed", throwable);
                                if (throwable instanceof IllegalArgumentException) {
                                    rc.fail(400, throwable);
                                } else {
                                    rc.fail(500, throwable);
                                }
                            }
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
                            throwable -> {
                                if (throwable instanceof IllegalArgumentException) {
                                    rc.fail(400, throwable);
                                } else {
                                    rc.fail(500, throwable);
                                }
                            }
                    );
        } catch (IllegalArgumentException e) {
            rc.fail(400, new IllegalArgumentException("Invalid document ID format"));
        }
    }

    private SoundFragmentFilterDTO parseFilterDTO(RoutingContext rc) {
        SoundFragmentFilterDTO filterDTO = new SoundFragmentFilterDTO();
        boolean hasAnyFilter = false;

        // Parse genres filter (comma-separated string values)
        String genresParam = rc.request().getParam("genre");
        if (genresParam != null && !genresParam.trim().isEmpty()) {
            List<String> genres = new ArrayList<>();
            String[] genreArray = genresParam.split(",");
            for (String genre : genreArray) {
                String trimmedGenre = genre.trim();
                if (!trimmedGenre.isEmpty()) {
                    genres.add(trimmedGenre);
                }
            }
            if (!genres.isEmpty()) {
                filterDTO.setGenres(genres);
                hasAnyFilter = true;
            }
        }

        // Parse sources filter (comma-separated enum values)
        String sourcesParam = rc.request().getParam("source");
        if (sourcesParam != null && !sourcesParam.trim().isEmpty()) {
            List<SourceType> sources = new ArrayList<>();
            String[] sourceArray = sourcesParam.split(",");
            for (String source : sourceArray) {
                String trimmedSource = source.trim();
                if (!trimmedSource.isEmpty()) {
                    try {
                        sources.add(SourceType.valueOf(trimmedSource));
                    } catch (IllegalArgumentException e) {
                        LOGGER.warn("Invalid source type: {}", trimmedSource);
                    }
                }
            }
            if (!sources.isEmpty()) {
                filterDTO.setSources(sources);
                hasAnyFilter = true;
            }
        }

        // Parse types filter (comma-separated enum values)
        String typesParam = rc.request().getParam("type");
        if (typesParam != null && !typesParam.trim().isEmpty()) {
            List<PlaylistItemType> types = new ArrayList<>();
            String[] typeArray = typesParam.split(",");
            for (String type : typeArray) {
                String trimmedType = type.trim();
                if (!trimmedType.isEmpty()) {
                    try {
                        types.add(PlaylistItemType.valueOf(trimmedType));
                    } catch (IllegalArgumentException e) {
                        LOGGER.warn("Invalid playlist item type: {}", trimmedType);
                    }
                }
            }
            if (!types.isEmpty()) {
                filterDTO.setTypes(types);
                hasAnyFilter = true;
            }
        }

        // Parse activated flag
        String activatedParam = rc.request().getParam("filterActivated");
        if (activatedParam != null && !activatedParam.trim().isEmpty()) {
            filterDTO.setActivated(Boolean.parseBoolean(activatedParam));
            hasAnyFilter = true;
        }

        return hasAnyFilter ? filterDTO : null;
    }
}