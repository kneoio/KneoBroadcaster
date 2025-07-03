package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.dto.BrandSoundFragmentDTO;
import io.kneo.broadcaster.dto.SoundFragmentDTO;
import io.kneo.broadcaster.dto.UploadFileDTO;
import io.kneo.broadcaster.dto.actions.SoundFragmentActionsFactory;
import io.kneo.broadcaster.model.FileData;
import io.kneo.broadcaster.model.SoundFragment;
import io.kneo.broadcaster.service.SoundFragmentService;
import io.kneo.broadcaster.util.FileSecurityUtils;
import io.kneo.core.controller.AbstractSecuredController;
import io.kneo.core.dto.actions.ActionBox;
import io.kneo.core.dto.cnst.PayloadType;
import io.kneo.core.dto.form.FormPage;
import io.kneo.core.dto.view.View;
import io.kneo.core.dto.view.ViewPage;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.IUser;
import io.kneo.core.repository.exception.DocumentHasNotFoundException;
import io.kneo.core.service.UserService;
import io.kneo.core.util.RuntimeUtil;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class SoundFragmentController extends AbstractSecuredController<SoundFragment, SoundFragmentDTO> {
    private static final Logger LOGGER = LoggerFactory.getLogger(SoundFragmentController.class);
    SoundFragmentService service;
    private String uploadDir;
    private Validator validator;
    private static final long MAX_FILE_SIZE_BYTES = 100 * 1024 * 1024;
    private static final long BODY_HANDLER_LIMIT = 1024L * 1024L * 1024L;

    private final ConcurrentHashMap<String, UploadFileDTO> uploadProgressMap = new ConcurrentHashMap<>();

    @Inject
    private Vertx vertx;

    public SoundFragmentController() {
        super(null);
    }

    @Inject
    public SoundFragmentController(UserService userService, SoundFragmentService service, BroadcasterConfig config, Validator validator) {
        super(userService);
        this.service = service;
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
                .setBodyLimit(BODY_HANDLER_LIMIT);

        BodyHandler jsonBodyHandler = BodyHandler.create().setHandleFileUploads(false);

        router.route(path + "*").handler(this::addHeaders);
        router.route(HttpMethod.GET, path).handler(this::get);
        router.route(HttpMethod.GET, path + "/available-soundfragments").handler(this::getForBrand);
        router.route(HttpMethod.GET, path + "/available-soundfragments/:id").handler(this::getForBrand);
        router.route(HttpMethod.GET, path + "/search").handler(this::search);
        router.route(HttpMethod.GET, path + "/:id").handler(this::getById);
        router.route(HttpMethod.GET, path + "/files/:id/:slug").handler(this::getBySlugName);
        router.route(HttpMethod.POST, path + "/:id?").handler(jsonBodyHandler).handler(this::upsert);
        router.route(HttpMethod.DELETE, path + "/:id").handler(this::delete);
        router.route(HttpMethod.POST, path + "/files/:id").handler(bodyHandler).handler(this::uploadFile);
        router.route(HttpMethod.GET, path + "/upload-progress/:uploadId").handler(this::getUploadProgress);
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
                        dto.setNewlyUploaded(List.of());
                        dto.setUploadedFiles(List.of());
                        dto.setRepresentedInBrands(List.of());
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

    private void search(RoutingContext rc) {
        String searchTerm = rc.request().getParam("q");
        int page = Integer.parseInt(rc.request().getParam("page", "1"));
        int size = Integer.parseInt(rc.request().getParam("size", "10"));

        if (searchTerm == null || searchTerm.trim().isEmpty()) {
            rc.fail(400, new IllegalArgumentException("Search term 'q' parameter is required"));
            return;
        }

        getContextUser(rc)
                .chain(user -> Uni.combine().all().unis(
                        service.getSearchCount(searchTerm, user),
                        service.search(searchTerm, size, (page - 1) * size, user)
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
            if (!validateJsonBody(rc)) return;

            SoundFragmentDTO dto = rc.body().asJsonObject().mapTo(SoundFragmentDTO.class);
            String id = rc.pathParam("id");

            if (!validateDTO(rc, dto, validator)) return;

            getContextUser(rc)
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
        getContextUser(rc)
                .chain(user -> service.archive(id, user))
                .subscribe().with(
                        count -> rc.response().setStatusCode(count > 0 ? 204 : 404).end(),
                        rc::fail
                );
    }

    private void uploadFile(RoutingContext rc) {
        if (rc.fileUploads().isEmpty()) {
            rc.fail(400, new IllegalArgumentException("No file uploaded"));
            return;
        }

        String id = rc.pathParam("id");
        FileUpload uploadedFile = rc.fileUploads().get(0);
        String uploadId = UUID.randomUUID().toString();

        LOGGER.info("Received file: {} bytes, limit: {} bytes", uploadedFile.size(), MAX_FILE_SIZE_BYTES);
        Path tempFile = Paths.get(uploadedFile.uploadedFileName());

        if (uploadedFile.size() > MAX_FILE_SIZE_BYTES) {
            rc.fail(413, new IllegalArgumentException(String.format("File too large. Maximum size is %d MB for audio files",
                    MAX_FILE_SIZE_BYTES / 1024 / 1024)));
            return;
        }

        String originalFileName = uploadedFile.fileName();
        if (!isValidAudioFile(originalFileName, uploadedFile.contentType())) {
            rc.fail(415, new IllegalArgumentException("Unsupported file type. Only audio files are allowed: " +
                    String.join(", ", SUPPORTED_AUDIO_EXTENSIONS)));
            return;
        }

        UploadFileDTO uploadDto = UploadFileDTO.builder()
                .id(uploadId)
                .name(originalFileName)
                .status("uploading")
                .percentage(0)
                .type(uploadedFile.contentType())
                .batchId(id)
                .build();

        uploadProgressMap.put(uploadId, uploadDto);

        getContextUser(rc)
                .chain(user -> {
                    processFileWithProgressReactive(uploadedFile, uploadId, id, user, originalFileName)
                            .subscribe().with(
                                    success -> {
                                        LOGGER.info("File processing completed for uploadId: {}", uploadId);
                                    },
                                    error -> {
                                        LOGGER.error("File processing failed for uploadId: {}", uploadId, error);
                                        updateUploadProgress(uploadId, 0, "error", null, null);
                                    }
                            );

                    return Uni.createFrom().item(uploadDto);
                })
                .subscribe().with(
                        uploadResponse -> rc.response()
                                .putHeader("Content-Type", "application/json")
                                .end(JsonObject.mapFrom(uploadResponse).encode()),
                        throwable -> {
                            if (throwable instanceof SecurityException) {
                                rc.fail(400, new SecurityException("Security violation: " + throwable.getMessage()));
                            } else if (throwable instanceof IllegalArgumentException) {
                                rc.fail(400, new IllegalArgumentException("Invalid input: " + throwable.getMessage()));
                            } else {
                                rc.fail(throwable);
                            }
                        }
                );
    }

    private Uni<Void> processFileWithProgressReactive(FileUpload uploadedFile, String uploadId, String entityId,
                                                      IUser user, String originalFileName) {
        return Uni.createFrom().item(() -> {
            updateUploadProgress(uploadId, 0, "uploading", null, null);

            String safeFileName;
            try {
                safeFileName = FileSecurityUtils.sanitizeFilename(originalFileName);
            } catch (SecurityException e) {
                LOGGER.warn("Unsafe filename rejected: {} from user: {}", originalFileName, user.getUserName());
                throw new IllegalArgumentException("Invalid filename: " + e.getMessage());
            }

            try {
                Path userDir = Files.createDirectories(Paths.get(uploadDir, user.getUserName()));
                String entityIdSafe = entityId != null ? entityId : "temp";

                if (!"temp".equals(entityIdSafe)) {
                    try {
                        UUID.fromString(entityIdSafe);
                    } catch (IllegalArgumentException e) {
                        LOGGER.warn("Invalid entity ID: {} from user: {}", entityIdSafe, user.getUserName());
                        throw new IllegalArgumentException("Invalid entity ID");
                    }
                }

                Path entityDir = Files.createDirectories(userDir.resolve(entityIdSafe));

                Path destination = FileSecurityUtils.secureResolve(entityDir, safeFileName);
                Path expectedBase = Paths.get(uploadDir, user.getUserName(), entityIdSafe);
                if (!FileSecurityUtils.isPathWithinBase(expectedBase, destination)) {
                    LOGGER.error("Security violation: Path traversal attempt by user {} with filename {}",
                            user.getUserName(), originalFileName);
                    throw new SecurityException("Invalid file path");
                }

                Path tempFile = Paths.get(uploadedFile.uploadedFileName());
                Files.move(tempFile, destination, StandardCopyOption.REPLACE_EXISTING);

                LOGGER.info("Moved uploaded file {} ({} MB) to {} for user: {}",
                        originalFileName, uploadedFile.size() / 1024 / 1024,
                        destination, user.getUserName());

                String fileUrl = String.format("/api/soundfragments/files/%s/%s", entityIdSafe, safeFileName);
                updateUploadProgress(uploadId, 100, "finished", fileUrl, destination.toString());

                return (Void) null;
            } catch (Exception e) {
                updateUploadProgress(uploadId, 0, "error", null, null);
                throw new RuntimeException(e);
            }
        }).emitOn(Infrastructure.getDefaultExecutor()).replaceWithVoid();
    }

    private void updateUploadProgress(String uploadId, Integer percentage, String status, String url, String fullPath) {
        UploadFileDTO dto = uploadProgressMap.get(uploadId);
        if (dto != null) {
            UploadFileDTO updatedDto = UploadFileDTO.builder()
                    .id(dto.getId())
                    .name(dto.getName())
                    .status(status)
                    .percentage(percentage)
                    .url(url)
                    .batchId(dto.getBatchId())
                    .type(dto.getType())
                    .fullPath(fullPath)
                    .thumbnailUrl(dto.getThumbnailUrl())
                    .build();

            uploadProgressMap.put(uploadId, updatedDto);
        }
    }

    private void getUploadProgress(RoutingContext rc) {
        String uploadId = rc.pathParam("uploadId");
        UploadFileDTO progress = uploadProgressMap.get(uploadId);

        if (progress == null) {
            rc.fail(404, new IllegalArgumentException("Upload not found"));
            return;
        }

        if ("finished".equals(progress.getStatus()) || "error".equals(progress.getStatus())) {
            vertx.setTimer(300000, timerId -> uploadProgressMap.remove(uploadId));
        }

        rc.response()
                .putHeader("Content-Type", "application/json")
                .end(JsonObject.mapFrom(progress).encode());
    }

    private void getBySlugName(RoutingContext rc) {
        String id = rc.pathParam("id");
        String requestedFileName = rc.pathParam("slug");

        getContextUser(rc)
                .chain(user -> {
                    try {
                        // Handle temp directory case
                        if ("temp".equals(id)) {
                            String safeFileName;
                            try {
                                safeFileName = FileSecurityUtils.sanitizeFilename(requestedFileName);
                            } catch (SecurityException e) {
                                LOGGER.warn("Unsafe filename in temp file request: {} from user: {}", requestedFileName, user.getUserName());
                                return Uni.createFrom().failure(new SecurityException("Invalid filename"));
                            }

                            Path baseDir = Paths.get(uploadDir, user.getUserName(), "temp");
                            Path secureFilePath = FileSecurityUtils.secureResolve(baseDir, safeFileName);
                            if (!FileSecurityUtils.isPathWithinBase(baseDir, secureFilePath)) {
                                LOGGER.error("Security violation: Path traversal attempt by user {} for temp file {}",
                                        user.getUserName(), requestedFileName);
                                return Uni.createFrom().failure(new SecurityException("Invalid file path"));
                            }

                            File file = secureFilePath.toFile();
                            if (file.exists()) {
                                try {
                                    Path canonicalFile = file.toPath().toRealPath();
                                    Path canonicalBase = baseDir.toRealPath();
                                    if (!canonicalFile.startsWith(canonicalBase)) {
                                        LOGGER.error("Security violation: Temp file outside base directory accessed by user {}", user.getUserName());
                                        return Uni.createFrom().failure(new SecurityException("File access denied"));
                                    }

                                    byte[] fileBytes = Files.readAllBytes(canonicalFile);
                                    String mimeType = Files.probeContentType(canonicalFile);
                                    return Uni.createFrom().item(new FileData(
                                            fileBytes,
                                            mimeType != null ? mimeType : "application/octet-stream"
                                    ));
                                } catch (IOException e) {
                                    LOGGER.error("Temp file read error for user {}, file: {}", user.getUserName(), safeFileName, e);
                                    return Uni.createFrom().failure(e);
                                }
                            } else {
                                return Uni.createFrom().failure(new FileNotFoundException("Temp file not found: " + safeFileName));
                            }
                        }

                        // Handle UUID directory case
                        try {
                            UUID.fromString(id);
                        } catch (IllegalArgumentException e) {
                            LOGGER.warn("Invalid entity ID in file request: {} from user: {}", id, user.getUserName());
                            return Uni.createFrom().failure(new IllegalArgumentException("Invalid entity ID"));
                        }

                        String safeFileName;
                        try {
                            safeFileName = FileSecurityUtils.sanitizeFilename(requestedFileName);
                        } catch (SecurityException e) {
                            LOGGER.warn("Unsafe filename in file request: {} from user: {}", requestedFileName, user.getUserName());
                            return Uni.createFrom().failure(new SecurityException("Invalid filename"));
                        }
                        Path baseDir = Paths.get(uploadDir, user.getUserName(), id);
                        Path secureFilePath = FileSecurityUtils.secureResolve(baseDir, safeFileName);
                        if (!FileSecurityUtils.isPathWithinBase(baseDir, secureFilePath)) {
                            LOGGER.error("Security violation: Path traversal attempt by user {} for file {}",
                                    user.getUserName(), requestedFileName);
                            return Uni.createFrom().failure(new SecurityException("Invalid file path"));
                        }

                        File file = secureFilePath.toFile();

                        if (file.exists()) {
                            try {
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
                                rc.fail(403, new SecurityException("Security violation: " + throwable.getMessage()));
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