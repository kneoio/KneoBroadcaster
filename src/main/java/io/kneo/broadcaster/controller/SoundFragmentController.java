package io.kneo.broadcaster.controller;

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
import io.kneo.core.service.UserService;
import io.kneo.core.util.RuntimeUtil;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.FileUpload;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class SoundFragmentController extends AbstractSecuredController<SoundFragment, SoundFragmentDTO> {

    SoundFragmentService service;

    public SoundFragmentController() {
        super(null);
    }

    @Inject
    public SoundFragmentController(UserService userService, SoundFragmentService service) {
        super(userService);
        this.service = service;
    }

    public void setupRoutes(Router router) {
        String path = "/api/:brand/soundfragments";
        router.route().handler(BodyHandler.create()
                .setHandleFileUploads(true)
                .setUploadsDirectory("uploads")
                .setDeleteUploadedFilesOnEnd(false)
                .setBodyLimit(100 * 1024 * 1024));

        router.route(path + "*").handler(this::addHeaders);
        router.route(HttpMethod.GET, path).handler(this::get);
        router.route(HttpMethod.GET, path + "/:id").handler(this::getById);
        router.route(HttpMethod.POST, path + "/upload").handler(this::uploadFile);
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

    private void upsert(RoutingContext rc) {
        String id = rc.pathParam("id");
        String contentType = rc.request().getHeader("Content-Type");
        SoundFragmentDTO dto;
        List<FileUpload> files;

        if (contentType != null && contentType.startsWith("multipart/form-data")) {
            files = rc.fileUploads();
            if (files.isEmpty()) {
                rc.fail(400);
                return;
            }
            String jsonData = rc.request().getFormAttribute("data");
            if (jsonData != null) {
                try {
                    dto = new JsonObject(jsonData).mapTo(SoundFragmentDTO.class);
                } catch (Exception e) {
                    System.err.println("Error parsing JSON: " + e.getMessage());
                    rc.fail(400);
                    return;
                }
            } else {
                dto = new SoundFragmentDTO();
            }
        } else {
            files = new ArrayList<>();
            JsonObject jsonObject = rc.body().asJsonObject();
            dto = jsonObject.mapTo(SoundFragmentDTO.class);
        }

        getContextUser(rc)
                .chain(user -> service.upsert(id, dto, files, user, LanguageCode.ENG))
                .subscribe().with(
                        doc -> {
                            int statusCode = id == null ? 201 : 200;
                            rc.response().setStatusCode(statusCode).end(JsonObject.mapFrom(doc).encode());
                        },
                        throwable -> {
                            // Debug logging
                            System.err.println("Error in upsert: " + throwable.getMessage());
                            throwable.printStackTrace();
                            rc.fail(throwable);
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

    private void uploadFile(RoutingContext rc) {
        String brand = rc.pathParam("brand");
        List<FileUpload> files = rc.fileUploads();
        if (files.isEmpty()) {
            rc.response()
                    .setStatusCode(400)
                    .putHeader("Content-Type", "text/plain")
                    .end("No file uploaded");
            return;
        }
        rc.response()
                .setStatusCode(202)
                .putHeader("Content-Type", "text/plain")
                .end("File upload accepted, processing started");
        getContextUser(rc)
                .chain(user -> service.streamDirectly(brand, files.getFirst()))
                .subscribe().with(
                        success -> LOGGER.info("File processed successfully"),
                        throwable -> LOGGER.error("Error processing file", throwable)
                );
    }
}