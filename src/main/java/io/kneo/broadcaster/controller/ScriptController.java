package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.dto.ScriptDTO;
import io.kneo.broadcaster.dto.ScriptSceneDTO;
import io.kneo.broadcaster.model.Script;
import io.kneo.broadcaster.service.ScriptService;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import io.kneo.broadcaster.util.ProblemDetailsUtil;

@ApplicationScoped
public class ScriptController extends AbstractSecuredController<Script, ScriptDTO> {
    @Inject
    ScriptService service;
    @Inject
    ScriptSceneService sceneService;
    private Validator validator;

    public ScriptController() {
        super(null);
    }

    @Inject
    public ScriptController(UserService userService, ScriptService service, Validator validator) {
        super(userService);
        this.service = service;
        this.validator = validator;
    }

    public void setupRoutes(Router router) {
        String path = "/api/scripts";
        router.route(path + "*").handler(BodyHandler.create());
        router.get(path).handler(this::getAll);
        router.get(path + "/:id").handler(this::getById);
        router.post(path).handler(this::upsert);
        router.post(path + "/:id").handler(this::upsert);
        router.delete(path + "/:id").handler(this::delete);
        router.get(path + "/:id/access").handler(this::getDocumentAccess);

        String scenesByScriptPath = "/api/scripts/:scriptId/scenes";
        router.route(scenesByScriptPath + "*").handler(BodyHandler.create());
        router.get(scenesByScriptPath).handler(this::getScenesForScript);
        router.post(scenesByScriptPath).handler(this::upsertSceneForScript);

        String scenePath = "/api/scenes";
        router.route(scenePath + "*").handler(BodyHandler.create());
        router.get(scenePath + "/:id").handler(this::getSceneById);
        router.post(scenePath + "/:id").handler(this::upsertScene);
        router.delete(scenePath + "/:id").handler(this::deleteScene);
    }

    private void getAll(RoutingContext rc) {
        int page = Integer.parseInt(rc.request().getParam("page", "1"));
        int size = Integer.parseInt(rc.request().getParam("size", "10"));

        getContextUser(rc, false, true)
                .chain(user -> Uni.combine().all().unis(
                        service.getAllCount(user),
                        service.getAll(size, (page - 1) * size, user)
                ).asTuple().map(tuple -> {
                    ViewPage viewPage = new ViewPage();
                    View<ScriptDTO> dtoEntries = new View<>(tuple.getItem2(),
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
    }

    private void getById(RoutingContext rc) {
        String id = rc.pathParam("id");
        LanguageCode languageCode = LanguageCode.valueOf(rc.request().getParam("lang", LanguageCode.en.name()));

        getContextUser(rc, false, true)
                .chain(user -> {
                    if ("new".equals(id)) {
                        ScriptDTO dto = new ScriptDTO();
                        return Uni.createFrom().item(Tuple2.of(dto, user));
                    }
                    return service.getDTO(UUID.fromString(id), user, languageCode)
                            .map(doc -> Tuple2.of(doc, user));
                })
                .subscribe().with(
                        tuple -> {
                            ScriptDTO doc = tuple.getItem1();
                            FormPage page = new FormPage();
                            page.addPayload(PayloadType.DOC_DATA, doc);
                            page.addPayload(PayloadType.CONTEXT_ACTIONS, new ActionBox());
                            rc.response().setStatusCode(200).end(JsonObject.mapFrom(page).encode());
                        },
                        rc::fail
                );
    }

    private void upsert(RoutingContext rc) {
        try {
            if (!validateJsonBody(rc)) return;

            ScriptDTO dto = rc.body().asJsonObject().mapTo(ScriptDTO.class);
            String id = rc.pathParam("id");

            java.util.Set<jakarta.validation.ConstraintViolation<ScriptDTO>> violations = validator.validate(dto);
            if (violations != null && !violations.isEmpty()) {
                Map<String, List<String>> fieldErrors = new HashMap<>();
                for (jakarta.validation.ConstraintViolation<ScriptDTO> v : violations) {
                    String field = v.getPropertyPath().toString();
                    fieldErrors.computeIfAbsent(field, k -> new ArrayList<>()).add(v.getMessage());
                }
                String detail = fieldErrors.entrySet().stream()
                        .flatMap(e -> e.getValue().stream().map(msg -> e.getKey() + ": " + msg))
                        .collect(Collectors.joining(", "));
                ProblemDetailsUtil.respondValidationError(rc, detail, fieldErrors);
                return;
            }

            getContextUser(rc, false, true)
                    .chain(user -> service.upsert(id, dto, user))
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

    private void getScenesForScript(RoutingContext rc) {
        String scriptId = rc.pathParam("scriptId");
        int page = Integer.parseInt(rc.request().getParam("page", "1"));
        int size = Integer.parseInt(rc.request().getParam("size", "10"));
        try {
            UUID scriptUUID = UUID.fromString(scriptId);
            getContextUser(rc, false, true)
                    .chain(user -> Uni.combine().all().unis(
                            sceneService.getForScriptCount(scriptUUID, user),
                            sceneService.getForScript(scriptUUID, size, (page - 1) * size, user)
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

    private void getSceneById(RoutingContext rc) {
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
            UUID scriptUUID = UUID.fromString(scriptId);
            getContextUser(rc, false, true)
                    .chain(user -> sceneService.upsert(null, scriptUUID, dto, user))
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

    private void upsertScene(RoutingContext rc) {
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
}
