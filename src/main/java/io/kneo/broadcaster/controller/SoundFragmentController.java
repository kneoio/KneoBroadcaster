package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.dto.BrandSoundFragmentDTO;
import io.kneo.broadcaster.dto.SoundFragmentDTO;
import io.kneo.broadcaster.dto.actions.SoundFragmentActionsFactory;
import io.kneo.broadcaster.model.SoundFragment;
import io.kneo.broadcaster.service.SoundFragmentService;
import io.kneo.core.controller.AbstractSecuredController;
import io.kneo.core.dto.actions.ActionBox;
import io.kneo.core.dto.cnst.PayloadType;
import io.kneo.core.dto.form.FormPage;
import io.kneo.core.dto.view.View;
import io.kneo.core.dto.view.ViewPage;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.repository.exception.DocumentModificationAccessException;
import io.kneo.core.service.UserService;
import io.kneo.core.util.RuntimeUtil;
import io.smallrye.mutiny.Uni;
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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;


@ApplicationScoped

public class SoundFragmentController extends AbstractSecuredController<SoundFragment, SoundFragmentDTO> {
    private static final Logger LOGGER = LoggerFactory.getLogger(SoundFragmentController.class);
    SoundFragmentService service;
    private BroadcasterConfig config;
    private String uploadDir;
    private String downloadDir;

    public SoundFragmentController() {
        super(null);
    }

    @Inject
    public SoundFragmentController(UserService userService, SoundFragmentService service, BroadcasterConfig config) {
        super(userService);
        this.service = service;
        this.config = config;
        downloadDir = config.getPathUploads() + "/sound-fragment-controller-download";
        uploadDir = config.getPathUploads() + "/sound-fragment-controller-upload";
    }

    public void setupRoutes(Router router) {
        String path = "/api/soundfragments";
        router.route().handler(BodyHandler.create()
                .setHandleFileUploads(true)
                .setDeleteUploadedFilesOnEnd(false)
                .setBodyLimit(100L * 1024 * 1024));
        router.route(path + "*").handler(this::addHeaders);
        router.route(HttpMethod.GET, path).handler(this::get);
        router.route(HttpMethod.GET, path + "/available-soundfragments").handler(this::getForBrand);
        router.route(HttpMethod.GET, path + "/:id").handler(this::getById);
        router.route(HttpMethod.GET, path + "/files/:filename").handler(this::getFileByName);
        router.route(HttpMethod.POST, path + "/files").handler(this::uploadFile);
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
        LanguageCode languageCode = LanguageCode.valueOf(rc.request().getParam("lang", LanguageCode.ENG.name()));
        getContextUser(rc)
                .chain(user -> service.getDTO(UUID.fromString(id), user, languageCode))
                .subscribe().with(
                        owner -> {
                            FormPage page = new FormPage();
                            page.addPayload(PayloadType.DOC_DATA, owner);
                            page.addPayload(PayloadType.CONTEXT_ACTIONS, new ActionBox());
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
                .chain(user -> service.getBrandSoundFragments(brandName, 300)
                        .map(fragments -> {
                            int totalCount = fragments.size();
                            ViewPage viewPage = new ViewPage();
                            View<BrandSoundFragmentDTO> dtoEntries = new View<>(fragments,
                                    totalCount, page,
                                    RuntimeUtil.countMaxPage(totalCount, size),
                                    size);
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
        String id = rc.pathParam("id");
        SoundFragmentDTO dto;
        try {
            JsonObject jsonObject = rc.body().asJsonObject();
            if (jsonObject == null) {
                rc.response().setStatusCode(400).end("Request body must be a valid JSON object.");
                return;
            }

            dto = jsonObject.mapTo(SoundFragmentDTO.class);
        } catch (Exception e) {
            System.err.println("Error parsing SoundFragmentDTO from JSON: " + e.getMessage());
            rc.response().setStatusCode(400).end("Invalid JSON payload.");
            return;
        }

        final SoundFragmentDTO finalDto = dto;
        getContextUser(rc)
                .chain(user -> {
                    String userName = user.getUserName();
                    if (finalDto.getUploadedFiles() != null && !finalDto.getUploadedFiles().isEmpty()) {
                        List<String> absoluteFilePaths = new ArrayList<>();
                        for (String fileName : finalDto.getUploadedFiles()) {
                            absoluteFilePaths.add(uploadDir + "/" + userName + "/" + fileName);
                        }
                        finalDto.setUploadedFiles(absoluteFilePaths);
                    }

                    return service.upsert(id, finalDto, user, LanguageCode.ENG);
                })
                .subscribe().with(
                        doc -> {
                            int statusCode = (id == null || id.isEmpty()) ? 201 : 200;
                            rc.response().setStatusCode(statusCode).end(JsonObject.mapFrom(doc).encode());
                        },
                        throwable -> {
                            if (throwable instanceof DocumentModificationAccessException) {
                                rc.response().setStatusCode(403).end("Not enough right to update");
                            } else {
                                System.err.println("Error in upsert: " + throwable.getMessage());
                                throwable.printStackTrace();
                                rc.fail(throwable);
                            }
                        }
                );
    }

    private void delete(RoutingContext rc) {
        String id = rc.pathParam("id");
        getContextUser(rc)
                .chain(user -> service.delete(id, user))
                .subscribe().with(
                        count -> rc.response().setStatusCode(count > 0 ? 204 : 404).end(),
                        rc::fail
                );
    }

    private void getFileByName(RoutingContext rc) {
        String requestedFileName = rc.pathParam("filename");
        getContextUser(rc).subscribe().with(user -> {
            String userName = user.getUserName();
            String filePath = downloadDir + "/" + userName + "/" + requestedFileName;
            File file = new File(filePath);
            if (file.exists() && file.isFile()) {
                try {
                    byte[] fileData = Files.readAllBytes(file.toPath());
                    rc.response()
                            .putHeader("Content-Disposition", "attachment; filename=\"" + file.getName() + "\"")
                            .putHeader("Content-Type", getMimeType(file))
                            .putHeader("Content-Length", String.valueOf(file.length()))
                            .setStatusCode(200)
                            .end(Buffer.buffer(fileData));
                } catch (IOException e) {
                    LOGGER.error("Error reading file {} from disk: {}", filePath, e.getMessage(), e);
                    rc.response()
                            .setStatusCode(500)
                            .putHeader("Content-Type", "text/plain")
                            .end("Error reading file from disk: " + e.getMessage());
                }
            } else {
                LOGGER.warn("File not found or access denied for user {}: {}", userName, filePath);
                rc.response()
                        .setStatusCode(404)
                        .putHeader("Content-Type", "text/plain")
                        .end("File not found or access denied");
            }
        }, throwable -> {
            LOGGER.error("Failed to get user context for getFileByName: {}", throwable.getMessage(), throwable);
            rc.response().setStatusCode(500).putHeader("Content-Type", "text/plain").end("Error processing user for file access");
        });
    }

    private void uploadFile(RoutingContext rc) {
        List<FileUpload> fileUploads = rc.fileUploads();

        if (fileUploads.isEmpty()) {
            rc.response().setStatusCode(400).putHeader("Content-Type", "text/plain").end("No file uploaded");
            return;
        }

        FileUpload uploadedFile = fileUploads.get(0);
        getContextUser(rc).subscribe().with(user -> {
            String userName = user.getUserName();
            String userSpecificUploadPath = uploadDir + "/" + userName;

            File userDir = new File(userSpecificUploadPath);
            if (!userDir.exists()) {
                if (!userDir.mkdirs()) {
                    LOGGER.error("Error creating target directory for user {}: {}", userName, userSpecificUploadPath);
                    rc.response().setStatusCode(500).putHeader("Content-Type", "text/plain").end("Error creating user directory");
                    return;
                }
            }

            String originalFileName = uploadedFile.fileName();
            String safeOriginalFileName = Paths.get(originalFileName).getFileName().toString();
            String uniqueFileName = "sfc_" + UUID.randomUUID() + "_" + safeOriginalFileName;
            String finalFilePath = userSpecificUploadPath + "/" + uniqueFileName;
            String fileUrl = String.format("%s/api/soundfragments/files/%s", config.getHost(), uniqueFileName);
            Path tempFilePath = Paths.get(uploadedFile.uploadedFileName());
            Path destinationFilePath = Paths.get(finalFilePath);

            try {
                Files.move(tempFilePath, destinationFilePath, StandardCopyOption.REPLACE_EXISTING);
                JsonObject responsePayload = new JsonObject()
                        .put("filePath", finalFilePath)
                        .put("fileUrl", fileUrl)
                        .put("fileName", uniqueFileName);

                rc.response()
                        .setStatusCode(200)
                        .putHeader("Content-Type", "application/json")
                        .end(responsePayload.encode());

            } catch (IOException e) {
                LOGGER.error("Error moving file {} to {}: {}", tempFilePath, destinationFilePath, e.getMessage());
                try {
                    Files.deleteIfExists(tempFilePath);
                } catch (IOException ex) {
                    LOGGER.error("Error deleting temporary file {}: {}", tempFilePath, ex.getMessage());
                }

                rc.response().setStatusCode(500).putHeader("Content-Type", "text/plain").end("Error saving file");
            }

        }, throwable -> {
            LOGGER.error("Failed to get user context for file upload: {}", throwable.getMessage(), throwable);
            fileUploads.forEach(fu -> {

                try {
                    Files.deleteIfExists(Paths.get(fu.uploadedFileName()));
                } catch (IOException e) {
                    LOGGER.warn("Could not delete temp file {} after user context failure: {}", fu.uploadedFileName(), e.getMessage());
                }
            });
            rc.response().setStatusCode(500).putHeader("Content-Type", "text/plain").end("Error processing user for upload");
        });
    }
}