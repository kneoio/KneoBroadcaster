package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.dto.BrandScriptDTO;
import io.kneo.broadcaster.dto.ScriptDTO;
import io.kneo.broadcaster.model.Script;
import io.kneo.broadcaster.service.ScriptDryRunService;
import io.kneo.broadcaster.service.ScriptService;
import io.kneo.broadcaster.service.util.BrandScriptUpdateService;
import io.kneo.broadcaster.util.ProblemDetailsUtil;
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
import io.vertx.pgclient.PgException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Validator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class ScriptController extends AbstractSecuredController<Script, ScriptDTO> {
    @Inject
    ScriptService service;
    @Inject
    BrandScriptUpdateService brandScriptUpdateService;
    @Inject
    ScriptDryRunService dryRunService;
    private Validator validator;

    public ScriptController() {
        super(null);
    }

    @Inject
    public ScriptController(UserService userService, ScriptService service, BrandScriptUpdateService brandScriptUpdateService, 
                           ScriptDryRunService dryRunService, Validator validator) {
        super(userService);
        this.service = service;
        this.brandScriptUpdateService = brandScriptUpdateService;
        this.dryRunService = dryRunService;
        this.validator = validator;
    }

    public void setupRoutes(Router router) {
        String path = "/api/scripts";
        router.route(path + "*").handler(BodyHandler.create());
        router.get(path).handler(this::getAll);
        router.get(path + "/shared").handler(this::getAllShared);
        router.get(path + "/available-scripts").handler(this::getForBrand);
        router.post(path + "/import").handler(this::importScript);
        router.get(path + "/:id/export").handler(this::exportScript);
        router.get(path + "/:id").handler(this::getById);
        router.post(path).handler(this::upsert);
        router.post(path + "/:id").handler(this::upsert);
        router.patch(path + "/:id/access-level").handler(this::updateAccessLevel);
        router.delete(path + "/:id").handler(this::delete);
        router.get(path + "/:id/access").handler(this::getDocumentAccess);
        router.post(path + "/:id/dry-run").handler(this::startDryRun);
        router.get(path + "/dry-run/stream").handler(this::dryRunStream);

        String brandScriptsPath = "/api/brands/:brandId/scripts";
        router.route(brandScriptsPath + "*").handler(BodyHandler.create());
        router.get(brandScriptsPath).handler(this::getScriptsForBrand);
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

    private void getAllShared(RoutingContext rc) {
        int page = Integer.parseInt(rc.request().getParam("page", "1"));
        int size = Integer.parseInt(rc.request().getParam("size", "10"));

        getContextUser(rc, false, true)
                .chain(user -> Uni.combine().all().unis(
                        service.getAllSharedCount(user),
                        service.getAllShared(size, (page - 1) * size, user)
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

    private void getForBrand(RoutingContext rc) {
        String brandName = rc.request().getParam("brand");
        int page = Integer.parseInt(rc.request().getParam("page", "1"));
        int size = Integer.parseInt(rc.request().getParam("size", "10"));

        getContextUser(rc, false, true)
                .chain(user -> Uni.combine().all().unis(
                        service.getBrandScripts(brandName, size, (page - 1) * size, user),
                        service.getCountBrandScripts(brandName, user)
                ).asTuple().map(tuple -> {
                    ViewPage viewPage = new ViewPage();
                    View<BrandScriptDTO> dtoEntries = new View<>(tuple.getItem1(),
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

    private void getScriptsForBrand(RoutingContext rc) {
        String brandId = rc.pathParam("brandId");
        int page = Integer.parseInt(rc.request().getParam("page", "1"));
        int size = Integer.parseInt(rc.request().getParam("size", "10"));
        try {
            UUID uuid = UUID.fromString(brandId);
            getContextUser(rc, false, true)
                    .chain(user -> Uni.combine().all().unis(
                            service.getForBrandCount(uuid, user),
                            service.getForBrand(uuid, size, (page - 1) * size, user)
                    ).asTuple().map(tuple -> {
                        ViewPage viewPage = new ViewPage();
                        View<BrandScriptDTO> dtoEntries = new View<>(tuple.getItem2(),
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
            rc.fail(400, new IllegalArgumentException("Invalid brand ID format"));
        }
    }

    private void startDryRun(RoutingContext rc) {
        String scriptId = rc.pathParam("id");

        try {
            if (!validateJsonBody(rc)) return;

            JsonObject body = rc.body().asJsonObject();
            String jobId = body.getString("jobId");
            String stationId = body.getString("stationId");

            if (jobId == null || jobId.isBlank()) {
                rc.fail(400, new IllegalArgumentException("jobId is required"));
                return;
            }
            if (stationId == null || stationId.isBlank()) {
                rc.fail(400, new IllegalArgumentException("stationId is required"));
                return;
            }

            UUID scriptUuid = UUID.fromString(scriptId);
            UUID stationUuid = UUID.fromString(stationId);

            getContextUser(rc, false, true)
                    .subscribe().with(
                            user -> {
                                dryRunService.startDryRun(jobId, scriptUuid, stationUuid, user);
                                JsonObject response = new JsonObject()
                                        .put("jobId", jobId)
                                        .put("message", "Dry-run started");
                                rc.response().setStatusCode(202).end(response.encode());
                            },
                            rc::fail
                    );
        } catch (IllegalArgumentException e) {
            rc.fail(400, new IllegalArgumentException("Invalid ID format"));
        } catch (Exception e) {
            rc.fail(400, new IllegalArgumentException("Invalid JSON payload"));
        }
    }

    private void dryRunStream(RoutingContext rc) {
        String jobId = rc.request().getParam("jobId");
        if (jobId == null || jobId.isBlank()) {
            rc.fail(400, new IllegalArgumentException("jobId is required"));
            return;
        }

        var resp = rc.response();
        resp.setChunked(true);
        resp.putHeader("Content-Type", "text/event-stream");
        resp.putHeader("Cache-Control", "no-cache");
        resp.putHeader("Connection", "keep-alive");

        java.util.function.Consumer<io.kneo.broadcaster.service.ScriptDryRunService.SseEvent> consumer = ev -> {
            try {
                String sb = "event: " + ev.type() + '\n' +
                        "data: " + (ev.data() != null ? ev.data().encode() : "{}") + '\n' + '\n';
                resp.write(sb);
            } catch (Exception ignored) { }
        };

        dryRunService.subscribe(jobId, consumer);

        // Unsubscribe when client disconnects
        resp.exceptionHandler(t -> dryRunService.unsubscribe(jobId, consumer));
        resp.endHandler(v -> dryRunService.unsubscribe(jobId, consumer));
    }

    private void exportScript(RoutingContext rc) {
        String id = rc.pathParam("id");
        boolean extended = "true".equals(rc.request().getParam("extended"));
        
        try {
            UUID scriptId = UUID.fromString(id);
            
            getContextUser(rc, false, true)
                    .chain(user -> service.exportScript(scriptId, user, extended))
                    .subscribe().with(
                            exportDTO -> {
                                rc.response()
                                        .setStatusCode(200)
                                        .putHeader("Content-Type", "application/json")
                                        .putHeader("Content-Disposition", "attachment; filename=\"script-" + id + ".json\"")
                                        .end(JsonObject.mapFrom(exportDTO).encodePrettily());
                            },
                            rc::fail
                    );
        } catch (IllegalArgumentException e) {
            rc.fail(400, new IllegalArgumentException("Invalid script ID format"));
        }
    }

    private void importScript(RoutingContext rc) {
        try {
            if (!validateJsonBody(rc)) return;
            
            io.kneo.broadcaster.dto.ScriptExportDTO importDTO = rc.body().asJsonObject().mapTo(io.kneo.broadcaster.dto.ScriptExportDTO.class);
            
            getContextUser(rc, false, true)
                    .chain(user -> service.importScript(importDTO, user))
                    .subscribe().with(
                            scriptDTO -> {
                                ViewPage viewPage = new ViewPage();
                                viewPage.addPayload(PayloadType.CONTEXT_ACTIONS, new ActionBox());
                                viewPage.addPayload(PayloadType.DOC_DATA, scriptDTO);
                                rc.response()
                                        .setStatusCode(201)
                                        .end(JsonObject.mapFrom(viewPage).encode());
                            },
                            failure -> {
                                if (failure instanceof PgException pgEx) {
                                    String sqlState = pgEx.getSqlState();
                                    if ("23505".equals(sqlState)) {
                                        String detail = pgEx.getErrorMessage();
                                        rc.fail(409, new IllegalStateException("Import failed: duplicate key constraint violation. " + detail));
                                        return;
                                    }
                                }
                                rc.fail(failure);
                            }
                    );
        } catch (Exception e) {
            rc.fail(400, new IllegalArgumentException("Invalid import data format: " + e.getMessage()));
        }
    }

    private void updateAccessLevel(RoutingContext rc) {
        try {
            if (!validateJsonBody(rc)) return;

            String id = rc.pathParam("id");
            JsonObject body = rc.body().asJsonObject();
            
            int accessLevel;
            Object accessLevelValue = body.getValue("accessLevel");
            
            if (accessLevelValue == null) {
                rc.fail(400, new IllegalArgumentException("accessLevel is required"));
                return;
            }
            
            if (accessLevelValue instanceof String accessLevelStr) {
                if ("PUBLIC".equalsIgnoreCase(accessLevelStr)) {
                    accessLevel = 1;
                } else if ("PRIVATE".equalsIgnoreCase(accessLevelStr)) {
                    accessLevel = 0;
                } else {
                    rc.fail(400, new IllegalArgumentException("Invalid accessLevel value. Expected 'PUBLIC', 'PRIVATE', or integer"));
                    return;
                }
            } else if (accessLevelValue instanceof Number) {
                accessLevel = ((Number) accessLevelValue).intValue();
            } else {
                rc.fail(400, new IllegalArgumentException("accessLevel must be a string or integer"));
                return;
            }

            getContextUser(rc, false, true)
                    .chain(user -> service.updateAccessLevel(id, accessLevel, user))
                    .subscribe().with(
                            dto -> rc.response().setStatusCode(200).end(JsonObject.mapFrom(dto).encode()),
                            rc::fail
                    );
        } catch (Exception e) {
            rc.fail(400, new IllegalArgumentException("Invalid JSON payload"));
        }
    }
}
