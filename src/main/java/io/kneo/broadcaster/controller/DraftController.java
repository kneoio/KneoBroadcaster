package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.agent.AgentClient;
import io.kneo.broadcaster.dto.DraftDTO;
import io.kneo.broadcaster.dto.agentrest.DraftTestReqDTO;
import io.kneo.broadcaster.dto.agentrest.TranslateReqDTO;
import io.kneo.broadcaster.dto.filter.DraftFilterDTO;
import io.kneo.broadcaster.model.Draft;
import io.kneo.broadcaster.service.DraftService;
import io.kneo.broadcaster.service.TranslateService;
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
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class DraftController extends AbstractSecuredController<Draft, DraftDTO> {
    private static final Logger LOGGER = LoggerFactory.getLogger(DraftController.class);

    private DraftService service;
    private Validator validator;
    private TranslateService translateService;

    private final AgentClient agentClient;

    public DraftController() {
        super(null);
        agentClient = null;
    }

    @Inject
    public DraftController(UserService userService, DraftService service, AgentClient agentClient, Validator validator, TranslateService translateService) {
        super(userService);
        this.service = service;
        this.agentClient = agentClient;
        this.validator = validator;
        this.translateService = translateService;
    }

    public void setupRoutes(Router router) {
        String path = "/api/drafts";
        router.route(path + "*").handler(BodyHandler.create());
        router.post(path + "/test").handler(this::testDraft);  // Must be before POST /api/drafts
        router.post(path + "/translate/start").handler(this::translateStart);
        router.get(path + "/translate/stream").handler(this::translateStream);
        router.get(path).handler(this::getAll);
        router.get(path + "/:id").handler(this::getById);
        router.post(path).handler(this::upsert);
        router.post(path + "/:id").handler(this::upsert);
        router.delete(path + "/:id").handler(this::delete);
    }

    private void getAll(RoutingContext rc) {
        int page = Integer.parseInt(rc.request().getParam("page", "1"));
        int size = Integer.parseInt(rc.request().getParam("size", "10"));
        DraftFilterDTO filter = parseFilterDTO(rc);

        getContextUser(rc, false, true)
                .chain(user -> Uni.combine().all().unis(
                        service.getAllCount(user, filter),
                        service.getAll(size, (page - 1) * size, user, filter)
                ).asTuple().map(tuple -> {
                    ViewPage viewPage = new ViewPage();
                    View<DraftDTO> dtoEntries = new View<>(
                            tuple.getItem2(),  // items
                            tuple.getItem1(),  // total count
                            page,
                            RuntimeUtil.countMaxPage(tuple.getItem1(), size),
                            size
                    );
                    viewPage.addPayload(PayloadType.VIEW_DATA, dtoEntries);
                    viewPage.addPayload(PayloadType.CONTEXT_ACTIONS, new ActionBox());
                    return viewPage;
                }))
                .subscribe().with(
                        viewPage -> rc.response().setStatusCode(200).end(JsonObject.mapFrom(viewPage).encode()),
                        throwable -> {
                            LOGGER.error("Failed to get all drafts", throwable);
                            rc.fail(throwable);
                        }
                );
    }
    
    private DraftFilterDTO parseFilterDTO(RoutingContext rc) {
        String filterParam = rc.request().getParam("filter");
        if (filterParam == null || filterParam.isBlank()) {
            return null;
        }
        
        DraftFilterDTO dto = new DraftFilterDTO();
        boolean any = false;
        
        try {
            String decodedFilter = URLDecoder.decode(filterParam, java.nio.charset.StandardCharsets.UTF_8);
            LOGGER.debug("Parsing filter parameter: {} -> decoded: {}", filterParam, decodedFilter);
            JsonObject json = new JsonObject(decodedFilter);
            
            if (json.containsKey("languageCode")) {
                dto.setLanguageCode(LanguageCode.valueOf(json.getString("languageCode")));
                any = true;
            }
            
            if (json.containsKey("archived")) {
                dto.setArchived(json.getInteger("archived"));
                any = true;
            }
            
            if (json.containsKey("enabled")) {
                dto.setEnabled(json.getBoolean("enabled"));
                any = true;
            }
            
            if (json.containsKey("master")) {
                dto.setMaster(json.getBoolean("master"));
                any = true;
            }
            
            if (json.containsKey("locked")) {
                dto.setLocked(json.getBoolean("locked"));
                any = true;
            }
            
            if (json.containsKey("activated")) {
                dto.setActivated(json.getBoolean("activated"));
                any = true;
            }
            
            return any ? dto : null;
            
        } catch (Exception e) {
            LOGGER.error("Error parsing filter parameters: '{}', error: {}", filterParam, e.getMessage(), e);
            return null;
        }
    }

    private void getById(RoutingContext rc) {
        String id = rc.pathParam("id");
        LanguageCode languageCode = LanguageCode.valueOf(rc.request().getParam("lang", LanguageCode.en.name()));

        getContextUser(rc, false, true)
                .chain(user -> {
                    if ("new".equals(id)) {
                        DraftDTO dto = new DraftDTO();
                        dto.setLanguageCode(LanguageCode.en);
                        dto.setArchived(0);
                        return Uni.createFrom().item(Tuple2.of(dto, user));
                    }
                    return service.getDTO(UUID.fromString(id), user, languageCode)
                            .map(doc -> Tuple2.of(doc, user));
                })
                .subscribe().with(
                        tuple -> {
                            DraftDTO doc = tuple.getItem1();
                            FormPage page = new FormPage();
                            page.addPayload(PayloadType.DOC_DATA, doc);
                            page.addPayload(PayloadType.CONTEXT_ACTIONS, new ActionBox());
                            rc.response().setStatusCode(200).end(JsonObject.mapFrom(page).encode());
                        },
                        throwable -> {
                            LOGGER.error("Failed to get draft by id: {}", id, throwable);
                            rc.fail(throwable);
                        }
                );
    }

    private void upsert(RoutingContext rc) {
        try {
            if (!validateJsonBody(rc)) {
                return;
            }

            String id = rc.pathParam("id");
            DraftDTO dto = rc.body().asJsonObject().mapTo(DraftDTO.class);

            Set<ConstraintViolation<DraftDTO>> violations = validator.validate(dto);
            if (violations != null && !violations.isEmpty()) {
                Map<String, List<String>> fieldErrors = new HashMap<>();
                for (ConstraintViolation<DraftDTO> v : violations) {
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
                    .chain(user -> service.upsert(id, dto, user, LanguageCode.en))
                    .subscribe().with(
                            doc -> rc.response().setStatusCode(id == null ? 201 : 200).end(JsonObject.mapFrom(doc).encode()),
                            throwable -> handleFailure(rc, throwable)
                    );
        } catch (Exception e) {
            LOGGER.error("Error in upsert operation", e);
            handleFailure(rc, e);
        }
    }

    private void delete(RoutingContext rc) {
        String id = rc.pathParam("id");
        getContextUser(rc, false, true)
                .chain(user -> service.delete(id, user))
                .subscribe().with(
                        count -> rc.response().setStatusCode(count > 0 ? 204 : 404).end(),
                        throwable -> handleFailure(rc, throwable)
                );
    }

    protected void handleFailure(RoutingContext rc, Throwable throwable) {
        if (throwable instanceof IllegalStateException
                || throwable instanceof IllegalArgumentException) {
            rc.fail(400, throwable);
        } else {
            LOGGER.error("Unexpected error in DraftController", throwable);
            rc.fail(throwable);
        }
    }

    private void testDraft(RoutingContext rc) {
        try {
            if (!validateJsonBody(rc)) return;

            DraftTestReqDTO dto = rc.body().asJsonObject().mapTo(DraftTestReqDTO.class);

            if (!validateDTO(rc, dto, validator)) return;

            getContextUser(rc, false, true)
                    .chain(user -> service.testDraft(dto, user))
                    .subscribe().with(
                            result -> rc.response()
                                    .setStatusCode(200)
                                    .putHeader("Content-Type", "text/plain")
                                    .end(result),
                            rc::fail
                    );

        } catch (Exception e) {
            rc.fail(400, new IllegalArgumentException("Invalid request: " + e.getMessage()));
        }
    }

    

    // --- SSE based translation flow ---
    private void translateStart(RoutingContext rc) {
        try {
            String jobId = rc.request().getParam("jobId");
            if (jobId == null || jobId.isBlank()) {
                rc.fail(400, new IllegalArgumentException("jobId is required"));
                return;
            }

            JsonArray array = rc.body().asJsonArray();
            if (array == null) {
                rc.fail(400, new IllegalArgumentException("Invalid request: expected a JSON array"));
                return;
            }
            List<TranslateReqDTO> dtos = new ArrayList<>();
            for (int i = 0; i < array.size(); i++) {
                JsonObject obj = array.getJsonObject(i);
                dtos.add(obj.mapTo(TranslateReqDTO.class));
            }

            for (TranslateReqDTO dto : dtos) {
                Set<ConstraintViolation<TranslateReqDTO>> violations = validator.validate(dto);
                if (violations != null && !violations.isEmpty()) {
                    Map<String, List<String>> fieldErrors = new HashMap<>();
                    for (ConstraintViolation<TranslateReqDTO> v : violations) {
                        String field = v.getPropertyPath().toString();
                        fieldErrors.computeIfAbsent(field, k -> new ArrayList<>()).add(v.getMessage());
                    }
                    String detail = fieldErrors.entrySet().stream()
                            .flatMap(e -> e.getValue().stream().map(msg -> e.getKey() + ": " + msg))
                            .collect(Collectors.joining(", "));
                    ProblemDetailsUtil.respondValidationError(rc, detail, fieldErrors);
                    return;
                }
            }

            getContextUser(rc, false, true)
                    .subscribe().with(user -> {
                        translateService.startJobForDrafts(jobId, dtos, user);
                        rc.response()
                                .setStatusCode(202)
                                .putHeader("Content-Type", "application/json")
                                .end(new JsonObject().put("jobId", jobId).encode());
                    }, rc::fail);

        } catch (Exception e) {
            rc.fail(400, new IllegalArgumentException("Invalid request: " + e.getMessage()));
        }
    }

    private void translateStream(RoutingContext rc) {
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

        java.util.function.Consumer<io.kneo.broadcaster.service.TranslateService.SseEvent> consumer = ev -> {
            try {
                StringBuilder sb = new StringBuilder();
                sb.append("event: ").append(ev.type()).append('\n');
                sb.append("data: ").append(ev.data() != null ? ev.data().encode() : "{}").append('\n').append('\n');
                resp.write(sb.toString());
            } catch (Exception ignored) { }
        };

        translateService.subscribe(jobId, consumer);

        // Unsubscribe when client disconnects
        resp.exceptionHandler(t -> translateService.unsubscribe(jobId, consumer));
        resp.endHandler(v -> translateService.unsubscribe(jobId, consumer));
    }
}
