package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.dto.ScriptSceneDTO;
import io.kneo.broadcaster.model.ScriptScene;
import io.kneo.broadcaster.service.ScriptSceneService;
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
import io.smallrye.mutiny.tuples.Tuple2;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Validator;

import java.util.UUID;

@ApplicationScoped
public class SceneController extends AbstractSecuredController<ScriptScene, ScriptSceneDTO> {
    @Inject
    ScriptSceneService sceneService;
    private Validator validator;

    public SceneController() {
        super(null);
    }

    @Inject
    public SceneController(UserService userService, ScriptSceneService sceneService, Validator validator) {
        super(userService);
        this.sceneService = sceneService;
        this.validator = validator;
    }

    public void setupRoutes(Router router) {
        String scenesByScriptPath = "/api/scripts/:scriptId/scenes";
        router.route(scenesByScriptPath + "*").handler(BodyHandler.create());
        router.get(scenesByScriptPath).handler(this::getScenesByScript);
        router.post(scenesByScriptPath).handler(this::upsertSceneForScript);

        String scenePath = "/api/scenes";
        router.route(scenePath + "*").handler(BodyHandler.create());
        router.get(scenePath + "/:id").handler(this::getById);
        router.post(scenePath + "/:id").handler(this::upsert);
        router.delete(scenePath + "/:id").handler(this::deleteScene);
        router.get(scenePath + "/:id/access").handler(this::getDocumentAccess);
    }

    private void getScenesByScript(RoutingContext rc) {
        String scriptId = rc.pathParam("scriptId");
        int page = Integer.parseInt(rc.request().getParam("page", "1"));
        int size = Integer.parseInt(rc.request().getParam("size", "10"));
        try {
            UUID uuid = UUID.fromString(scriptId);
            getContextUser(rc, false, true)
                    .chain(user -> Uni.combine().all().unis(
                            sceneService.getAllCount(uuid, user),
                            sceneService.getAll(uuid, size, (page - 1) * size, user)
                    ).asTuple().map(tuple -> {
                        ViewPage viewPage = new ViewPage();
                        View<ScriptSceneDTO> dtoEntries = new View<>(tuple.getItem2(),
                                tuple.getItem1(), page,
                                RuntimeUtil.countMaxPage(tuple.getItem1(), size),
                                size);
                        viewPage.addPayload(PayloadType.VIEW_DATA, dtoEntries);
                        viewPage.addPayload(PayloadType.CONTEXT_ACTIONS, new ActionBox());
                        return viewPage;
                    }))
                    .subscribe().with(
                            viewPage -> rc.response().setStatusCode(200).end(JsonObject.mapFrom(viewPage).encode()),
                            rc::fail
                    );
        } catch (IllegalArgumentException e) {
            rc.fail(400, new IllegalArgumentException("Invalid script ID format"));
        }
    }

    private void getById(RoutingContext rc) {
        String id = rc.pathParam("id");
        LanguageCode languageCode = LanguageCode.valueOf(rc.request().getParam("lang", LanguageCode.en.name()));
        getContextUser(rc, false, true)
                .chain(user -> {
                    if ("new".equals(id)) {
                        ScriptSceneDTO dto = new ScriptSceneDTO();
                        return Uni.createFrom().item(Tuple2.of(dto, user));
                    } else {
                        return sceneService.getDTO(UUID.fromString(id), user, languageCode)
                                .map(doc -> Tuple2.of(doc, user));
                    }
                })
                .subscribe().with(
                        tuple -> {
                            ScriptSceneDTO doc = tuple.getItem1();
                            FormPage page = new FormPage();
                            page.addPayload(PayloadType.DOC_DATA, doc);
                            page.addPayload(PayloadType.CONTEXT_ACTIONS, new ActionBox());
                            rc.response().setStatusCode(200).end(JsonObject.mapFrom(page).encode());
                        },
                        rc::fail
                );
    }

    private void upsertSceneForScript(RoutingContext rc) {
        try {
            if (!validateJsonBody(rc)) return;
            String scriptId = rc.pathParam("scriptId");
            ScriptSceneDTO dto = rc.body().asJsonObject().mapTo(ScriptSceneDTO.class);
            if (!validateDTO(rc, dto, validator)) return;
            UUID uuid = UUID.fromString(scriptId);
            getContextUser(rc, false, true)
                    .chain(user -> sceneService.upsert(null, uuid, dto, user))
                    .subscribe().with(
                            doc -> sendUpsertResponse(rc, doc, null),
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

    private void upsert(RoutingContext rc) {
        try {
            if (!validateJsonBody(rc)) return;
            String id = rc.pathParam("id");
            ScriptSceneDTO dto = rc.body().asJsonObject().mapTo(ScriptSceneDTO.class);
            if (!validateDTO(rc, dto, validator)) return;
            getContextUser(rc, false, true)
                    .chain(user -> sceneService.upsert(id, null, dto, user))
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

    private void deleteScene(RoutingContext rc) {
        String id = rc.pathParam("id");
        getContextUser(rc, false, true)
                .chain(user -> sceneService.archive(id, user))
                .subscribe().with(
                        count -> rc.response().setStatusCode(count > 0 ? 204 : 404).end(),
                        rc::fail
                );
    }

    private void getDocumentAccess(RoutingContext rc) {
        String id = rc.pathParam("id");

        try {
            UUID documentId = UUID.fromString(id);

            getContextUser(rc, false, true)
                    .chain(user -> sceneService.getDocumentAccess(documentId, user))
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
}
