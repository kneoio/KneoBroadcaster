package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.dto.BrandSoundFragmentDTO;
import io.kneo.broadcaster.dto.SoundFragmentDTO;
import io.kneo.broadcaster.dto.actions.SoundFragmentActionsFactory;
import io.kneo.broadcaster.model.FileData;
import io.kneo.broadcaster.model.SoundFragment;
import io.kneo.broadcaster.repository.exceptions.UploadAbsenceException;
import io.kneo.broadcaster.service.SoundFragmentService;
import io.kneo.core.controller.AbstractSecuredController;
import io.kneo.core.dto.actions.ActionBox;
import io.kneo.core.dto.cnst.PayloadType;
import io.kneo.core.dto.form.FormPage;
import io.kneo.core.dto.view.View;
import io.kneo.core.dto.view.ViewPage;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.repository.exception.DocumentHasNotFoundException;
import io.kneo.core.repository.exception.DocumentModificationAccessException;
import io.kneo.core.service.UserService;
import io.kneo.core.util.RuntimeUtil;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.tuples.Tuple2;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.FileUpload;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class SoundFragmentController extends AbstractSecuredController<SoundFragment, SoundFragmentDTO> {
    private static final Logger LOGGER = LoggerFactory.getLogger(SoundFragmentController.class);
    SoundFragmentService service;
    private BroadcasterConfig config;
    private String uploadDir;
    private Validator validator;

    public SoundFragmentController() {
        super(null);
    }

    @Inject
    public SoundFragmentController(UserService userService, SoundFragmentService service, BroadcasterConfig config, Validator validator) {
        super(userService);
        this.service = service;
        this.config = config;
        uploadDir = config.getPathUploads() + "/sound-fragments-controller";
        this.validator = validator;
    }

    public void setupRoutes(Router router) {
        String path = "/api/soundfragments";
        router.route().handler(BodyHandler.create()
                .setHandleFileUploads(true)
                .setMergeFormAttributes(true)
                .setUploadsDirectory(uploadDir)
                //.setBodyLimit(100L * 1024 * 1024)
                .setDeleteUploadedFilesOnEnd(false));
        router.route(path + "*").handler(this::addHeaders);
        router.route(HttpMethod.GET, path).handler(this::get);
        router.route(HttpMethod.GET, path + "/available-soundfragments").handler(this::getForBrand);
        router.route(HttpMethod.GET, path + "/:id").handler(this::getById);
        router.route(HttpMethod.GET, path + "/files/:id/:slug").handler(this::getBySlugName);
        router.route(HttpMethod.POST, path + "/files/:id").handler(this::uploadFile);
        router.route(HttpMethod.POST, path + "/:id?").handler(this::upsert);
        router.route(HttpMethod.DELETE, path + "/:id").handler(this::delete);

    }

    private void get(RoutingContext rc) {
        int page = Integer.parseInt(rc.request().getParam("page", "1"));
        int size = Integer.parseInt(rc.request().getParam("size", "10"));
        getContextUser(rc)
                .chain(user -> Uni.combine().all().unis(
                        service.getAllCount(user),
                        service.getAll(size, (page - 1) * size, user)
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

        getContextUser(rc)
                .chain(user -> {
                    if ("new".equals(id)) {
                        SoundFragmentDTO dto = new SoundFragmentDTO();
                        dto.setAuthor(user.getUserName());
                        dto.setLastModifier(user.getUserName());
                        return Uni.createFrom().item(Tuple2.of(dto, user));
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
        int page = Integer.parseInt(rc.request().getParam("page", "1"));
        int size = Integer.parseInt(rc.request().getParam("size", "10"));

        getContextUser(rc)
                .chain(user -> service.getBrandSoundFragments(brandName, 300, user)
                        .map(allFragments -> {
                            int totalCount = allFragments.size();

                            int startIndex = (page - 1) * size;
                            int endIndex = Math.min(startIndex + size, totalCount);

                            java.util.List<BrandSoundFragmentDTO> paginatedFragments =
                                    allFragments.subList(startIndex, endIndex);

                            ViewPage viewPage = new ViewPage();
                            View<BrandSoundFragmentDTO> dtoEntries = new View<>(
                                    paginatedFragments,
                                    totalCount,
                                    page,
                                    RuntimeUtil.countMaxPage(totalCount, size),
                                    size
                            );
                            viewPage.addPayload(PayloadType.VIEW_DATA, dtoEntries);
                            ActionBox actions = SoundFragmentActionsFactory.getViewActions(user.getActivatedRoles());
                            viewPage.addPayload(PayloadType.CONTEXT_ACTIONS, actions);
                            return viewPage;
                        }))
                .subscribe().with(
                        viewPage -> rc.response().setStatusCode(200).end(JsonObject.mapFrom(viewPage).encode()),
                        throwable -> {
                            LOGGER.error("Failed to fetch fragments for brand: {}", brandName, throwable);
                            rc.fail(throwable);
                        }
                );
    }

    private void upsert(RoutingContext rc) {
        try {
            JsonObject json = rc.body().asJsonObject();
            if (json == null) {
                rc.response().setStatusCode(400).end("Request body must be a valid JSON object");
                return;
            }

            SoundFragmentDTO dto = json.mapTo(SoundFragmentDTO.class);
            String id = rc.pathParam("id");

            Set<ConstraintViolation<SoundFragmentDTO>> violations = validator.validate(dto);
            if (!violations.isEmpty()) {
                handleValidationErrors(rc, violations);
                return;
            }

            getContextUser(rc)
                    .chain(user -> {
                        return service.upsert(id, dto, user, LanguageCode.en);
                    })
                    .subscribe().with(
                            doc -> rc.response()
                                    .setStatusCode(id == null ? 201 : 200)
                                    .end(JsonObject.mapFrom(doc).encode()),
                            throwable -> {
                                if (throwable instanceof DocumentModificationAccessException) {
                                    rc.response().setStatusCode(403).end("Not enough rights to update");
                                } else if (throwable instanceof UploadAbsenceException) {
                                    rc.response().setStatusCode(400).end(throwable.getMessage());
                                } else {
                                    rc.fail(throwable);
                                }
                            }
                    );

        } catch (Exception e) {
            rc.response().setStatusCode(400).end("Invalid JSON payload");
        }
    }

    private void delete(RoutingContext rc) {
        String id = rc.pathParam("id");
        getContextUser(rc)
                .chain(user -> service.archive(id, user))
                .subscribe().with(
                        count -> rc.response().setStatusCode(count > 0 ? 204 : 404).end(),
                        rc::fail
                );
    }

    private void getBySlugName(RoutingContext rc) {
        String id = rc.pathParam("id");
        String requestedFileName = rc.pathParam("slug");

        getContextUser(rc)
                .chain(user -> {
                    Path destinationDir = Paths.get(uploadDir, user.getUserName(), id);
                    File file = destinationDir.resolve(requestedFileName).toFile();

                    if (file.exists()) {
                        try {
                            byte[] fileBytes = Files.readAllBytes(file.toPath());
                            String mimeType = Files.probeContentType(file.toPath());
                            return Uni.createFrom().item(new FileData(
                                    fileBytes,
                                    mimeType != null ? mimeType : "application/octet-stream"
                            ));
                        } catch (IOException e) {
                            return Uni.createFrom().failure(e);
                        }
                    }

                    return service.getFile(UUID.fromString(id), requestedFileName, user)
                            .onItem().transform(fileMetadata -> {
                                return new FileData(fileMetadata.getFileBin(), fileMetadata.getMimeType());
                            });
                })
                .subscribe().with(
                        fileData -> {
                            if (fileData == null || fileData.getData() == null || fileData.getData().length == 0) {
                                rc.response()
                                        .setStatusCode(404)
                                        .end("File content not available");
                                return;
                            }

                            rc.response()
                                    .putHeader("Content-Disposition", "attachment; filename=\"" + requestedFileName + "\"")
                                    .putHeader("Content-Type", fileData.getMimeType())
                                    .putHeader("Content-Length", String.valueOf(fileData.getData().length))
                                    .end(Buffer.buffer(fileData.getData()));
                        },
                        throwable -> {
                            if (throwable instanceof FileNotFoundException ||
                                    throwable instanceof DocumentHasNotFoundException) {
                                rc.response().setStatusCode(404).end("File not found");
                            } else {
                                LOGGER.error("File retrieval error", throwable);
                                rc.fail(500, throwable);
                            }
                        }
                );
    }

    private void uploadFile(RoutingContext rc) {
        if (rc.fileUploads().isEmpty()) {
            rc.fail(400);
            return;
        }
        String id = rc.pathParam("id");
        FileUpload uploadedFile = rc.fileUploads().get(0);
        Path tempFile = Paths.get(uploadedFile.uploadedFileName());

        getContextUser(rc)
                .chain(user -> Uni.createFrom().emitter(emitter -> {
                    if (user.getId() > 0) {
                        try {
                            String fileName = uploadedFile.fileName();
                            Path destination = Files.createDirectories(Paths.get(uploadDir, user.getUserName(), id)).resolve(fileName);

                            Path movedTo = Files.move(tempFile, destination, StandardCopyOption.REPLACE_EXISTING);
                            LOGGER.info("Uploaded file moved to {}", movedTo);
                            emitter.complete(config.getHost() + "/api/soundfragments/files/" + id + "/" + fileName);
                        } catch (IOException e) {
                            emitter.fail(e);
                        }
                    } else {
                        rc.response().setStatusCode(403).end("Unauthorized user");
                    }
                }))
                .subscribe().with(
                        url -> rc.response()
                                .putHeader("Content-Type", "application/json")
                                .end(new JsonObject().put("fileUrl", url).encode()),
                        rc::fail
                );
    }

}