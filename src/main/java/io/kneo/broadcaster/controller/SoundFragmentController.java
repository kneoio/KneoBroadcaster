package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.dto.BrandSoundFragmentDTO;
import io.kneo.broadcaster.dto.SoundFragmentDTO;
import io.kneo.broadcaster.dto.UploadFileDTO;
import io.kneo.broadcaster.dto.actions.SoundFragmentActionsFactory;
import io.kneo.broadcaster.model.FileData;
import io.kneo.broadcaster.model.SoundFragment;
import io.kneo.broadcaster.repository.exceptions.UploadAbsenceException;
import io.kneo.broadcaster.service.SoundFragmentService;
import io.kneo.broadcaster.util.FileSecurityUtils;
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
    private static final long MAX_FILE_SIZE_BYTES = 50 * 1024 * 1024; // 50MB
    private static final long MAX_REQUEST_SIZE_BYTES = 60 * 1024 * 1024; // 60MB
    private static final int UPLOAD_TIMEOUT_SECONDS = 300; // 5 minutes


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

        BodyHandler bodyHandler = BodyHandler.create()
                .setHandleFileUploads(true)
                .setMergeFormAttributes(true)
                .setUploadsDirectory(uploadDir)
                .setDeleteUploadedFilesOnEnd(false)
                .setBodyLimit(MAX_REQUEST_SIZE_BYTES);

        router.route(path + "*").handler(this::addHeaders);
        router.route(HttpMethod.GET, path).handler(this::get);
        router.route(HttpMethod.GET, path + "/available-soundfragments").handler(this::getForBrand);
        router.route(HttpMethod.GET, path + "/available-soundfragments/:id").handler(this::getForBrand);
        router.route(HttpMethod.GET, path + "/:id").handler(this::getById);
        router.route(HttpMethod.GET, path + "/files/:id/:slug").handler(this::getBySlugName);
        router.route(HttpMethod.POST, path + "/:id?").handler(this::upsert);
        router.route(HttpMethod.DELETE, path + "/:id").handler(this::delete);
        router.route(HttpMethod.POST, path + "/files/:id").handler(bodyHandler).handler(this::uploadFile);
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
                .chain(user -> Uni.combine().all().unis(
                        service.getBrandSoundFragments(brandName, size, (page - 1) * size, true, user),
                        service.getCountBrandSoundFragments(brandName, user)
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
                    .chain(user -> service.upsert(id, dto, user, LanguageCode.en))
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

    private void uploadFile(RoutingContext rc) {
        if (rc.fileUploads().isEmpty()) {
            rc.response().setStatusCode(400).end("No file uploaded");
            return;
        }

        String id = rc.pathParam("id");
        FileUpload uploadedFile = rc.fileUploads().get(0);
        Path tempFile = Paths.get(uploadedFile.uploadedFileName());

        if (uploadedFile.size() > MAX_FILE_SIZE_BYTES) {
            rc.response().setStatusCode(413)
                    .end(String.format("File too large. Maximum size is %d MB for audio files",
                            MAX_FILE_SIZE_BYTES / 1024 / 1024));
            return;
        }

        String originalFileName = uploadedFile.fileName();
        if (!isValidAudioFile(originalFileName, uploadedFile.contentType())) {
            rc.response().setStatusCode(415)
                    .end("Unsupported file type. Only audio files are allowed: " +
                            String.join(", ", SUPPORTED_AUDIO_EXTENSIONS));
            return;
        }

        getContextUser(rc)
                .chain(user -> {
                    try {
                        // SECURITY: Sanitize the filename to prevent path traversal
                        String safeFileName;
                        try {
                            safeFileName = FileSecurityUtils.sanitizeFilename(originalFileName);
                        } catch (SecurityException e) {
                            LOGGER.warn("Unsafe filename rejected: {} from user: {}", originalFileName, user.getUserName());
                            return Uni.createFrom().failure(new IllegalArgumentException("Invalid filename: " + e.getMessage()));
                        }



                        // Create user and entity directories securely
                        Path userDir = Files.createDirectories(Paths.get(uploadDir, user.getUserName()));
                        String entityIdSafe = id != null ? id : "temp";

                        // Validate entity ID is a valid UUID or "temp"
                        if (!"temp".equals(entityIdSafe)) {
                            try {
                                UUID.fromString(entityIdSafe);
                            } catch (IllegalArgumentException e) {
                                LOGGER.warn("Invalid entity ID: {} from user: {}", entityIdSafe, user.getUserName());
                                return Uni.createFrom().failure(new IllegalArgumentException("Invalid entity ID"));
                            }
                        }

                        Path entityDir = Files.createDirectories(userDir.resolve(entityIdSafe));

                        // SECURITY: Use secure path resolution
                        Path destination = FileSecurityUtils.secureResolve(entityDir, safeFileName);

                        // Verify the destination is within our expected directory structure
                        Path expectedBase = Paths.get(uploadDir, user.getUserName(), entityIdSafe);
                        if (!FileSecurityUtils.isPathWithinBase(expectedBase, destination)) {
                            LOGGER.error("Security violation: Path traversal attempt by user {} with filename {}",
                                    user.getUserName(), originalFileName);
                            return Uni.createFrom().failure(new SecurityException("Invalid file path"));
                        }

                        // Move file to secure location
                        Path movedTo = Files.move(tempFile, destination, StandardCopyOption.REPLACE_EXISTING);
                        LOGGER.info("Audio file uploaded: {} ({} MB) for user: {}",
                                movedTo, uploadedFile.size() / 1024 / 1024, user.getUserName());

                        String fileId = UUID.randomUUID().toString();
                        UploadFileDTO uploadResponse = UploadFileDTO.builder()
                                .id(fileId)
                                .name(safeFileName)
                                .status("finished")
                                .url("/api/soundfragments/files/" + entityIdSafe + "/" + safeFileName)
                                .percentage(100)
                                .build();

                        return Uni.createFrom().item(uploadResponse);

                    } catch (SecurityException e) {
                        LOGGER.error("Security violation in file upload: {}", e.getMessage());
                        return Uni.createFrom().failure(e);
                    } catch (IOException e) {
                        LOGGER.error("File operation failed", e);
                        return Uni.createFrom().failure(e);
                    }
                })
                .subscribe().with(
                        uploadResponse -> rc.response()
                                .putHeader("Content-Type", "application/json")
                                .end(JsonObject.mapFrom(uploadResponse).encode()),
                        throwable -> {
                            if (throwable instanceof SecurityException) {
                                rc.response().setStatusCode(400).end("Security violation: " + throwable.getMessage());
                            } else if (throwable instanceof IllegalArgumentException) {
                                rc.response().setStatusCode(400).end("Invalid input: " + throwable.getMessage());

                            } else {
                                rc.fail(throwable);
                            }
                        }
                );
    }

    // Update the getBySlugName method with security fixes
    private void getBySlugName(RoutingContext rc) {
        String id = rc.pathParam("id");
        String requestedFileName = rc.pathParam("slug");

        getContextUser(rc)
                .chain(user -> {
                    try {
                        // SECURITY: Validate entity ID
                        try {
                            UUID.fromString(id);
                        } catch (IllegalArgumentException e) {
                            LOGGER.warn("Invalid entity ID in file request: {} from user: {}", id, user.getUserName());
                            return Uni.createFrom().failure(new IllegalArgumentException("Invalid entity ID"));
                        }

                        // SECURITY: Sanitize the requested filename
                        String safeFileName;
                        try {
                            safeFileName = FileSecurityUtils.sanitizeFilename(requestedFileName);
                        } catch (SecurityException e) {
                            LOGGER.warn("Unsafe filename in file request: {} from user: {}", requestedFileName, user.getUserName());
                            return Uni.createFrom().failure(new SecurityException("Invalid filename"));
                        }

                        // Create secure path
                        Path baseDir = Paths.get(uploadDir, user.getUserName(), id);
                        Path secureFilePath = FileSecurityUtils.secureResolve(baseDir, safeFileName);

                        // Double-check the resolved path is within expected bounds
                        if (!FileSecurityUtils.isPathWithinBase(baseDir, secureFilePath)) {
                            LOGGER.error("Security violation: Path traversal attempt by user {} for file {}",
                                    user.getUserName(), requestedFileName);
                            return Uni.createFrom().failure(new SecurityException("Invalid file path"));
                        }

                        File file = secureFilePath.toFile();

                        if (file.exists()) {
                            try {
                                // Additional security check: ensure file is actually within our directory
                                Path canonicalFile = file.toPath().toRealPath();
                                Path canonicalBase = baseDir.toRealPath();
                                if (!canonicalFile.startsWith(canonicalBase)) {
                                    LOGGER.error("Security violation: File outside base directory accessed by user {}", user.getUserName());
                                    return Uni.createFrom().failure(new SecurityException("File access denied"));
                                }

                                byte[] fileBytes = Files.readAllBytes(canonicalFile);
                                String mimeType = Files.probeContentType(canonicalFile);
                                return Uni.createFrom().item(new FileData(
                                        fileBytes,
                                        mimeType != null ? mimeType : "application/octet-stream"
                                ));
                            } catch (IOException e) {
                                LOGGER.error("File read error for user {}, file: {}", user.getUserName(), safeFileName, e);
                                return Uni.createFrom().failure(e);
                            }
                        }

                        // If local file doesn't exist, try database/storage
                        return service.getFile(UUID.fromString(id), safeFileName, user)
                                .onItem().transform(fileMetadata ->
                                        new FileData(fileMetadata.getFileBin(), fileMetadata.getMimeType()));

                    } catch (SecurityException | IllegalArgumentException e) {
                        return Uni.createFrom().failure(e);
                    }
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
                                    .putHeader("Content-Disposition", "attachment; filename=\"" +
                                            FileSecurityUtils.sanitizeFilename(requestedFileName) + "\"")
                                    .putHeader("Content-Type", fileData.getMimeType())
                                    .putHeader("Content-Length", String.valueOf(fileData.getData().length))
                                    .end(Buffer.buffer(fileData.getData()));
                        },
                        throwable -> {
                            if (throwable instanceof SecurityException) {
                                LOGGER.warn("Security violation in file access: {}", throwable.getMessage());
                                rc.response().setStatusCode(403).end("Access denied");
                            } else if (throwable instanceof IllegalArgumentException) {
                                rc.response().setStatusCode(400).end("Invalid request");
                            } else if (throwable instanceof FileNotFoundException ||
                                    throwable instanceof DocumentHasNotFoundException) {
                                rc.response().setStatusCode(404).end("File not found");
                            } else {
                                LOGGER.error("File retrieval error", throwable);
                                rc.fail(500, throwable);
                            }
                        }
                );
    }

    private boolean isValidAudioFile(String filename, String contentType) {
        if (filename == null || filename.trim().isEmpty()) {
            return false;
        }

        String extension = getFileExtension(filename.toLowerCase());
        boolean validExtension = SUPPORTED_AUDIO_EXTENSIONS.contains(extension);

        boolean validMimeType = contentType != null &&
                SUPPORTED_AUDIO_MIME_TYPES.stream().anyMatch(contentType::startsWith);

        return validExtension || validMimeType;
    }

    private String getFileExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        if (lastDot > 0 && lastDot < filename.length() - 1) {
            return filename.substring(lastDot + 1);
        }
        return "";
    }

    private static final Set<String> SUPPORTED_AUDIO_EXTENSIONS = Set.of(
            "mp3", "wav", "flac", "aac", "ogg", "m4a"
    );

    private static final Set<String> SUPPORTED_AUDIO_MIME_TYPES = Set.of(
            "audio/mpeg", "audio/wav", "audio/wave", "audio/x-wav",
            "audio/flac", "audio/x-flac", "audio/aac", "audio/ogg",
            "audio/mp4", "audio/x-m4a"
    );
}