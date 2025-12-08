package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.agent.AgentClient;
import io.kneo.broadcaster.dto.PromptDTO;
import io.kneo.broadcaster.dto.agentrest.PromptTestReqDTO;
import io.kneo.broadcaster.dto.agentrest.TranslateReqDTO;
import io.kneo.broadcaster.dto.filter.PromptFilterDTO;
import io.kneo.broadcaster.model.Prompt;
import io.kneo.broadcaster.model.aiagent.PromptType;
import io.kneo.broadcaster.service.PromptService;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class PromptController extends AbstractSecuredController<Prompt, PromptDTO> {
    private static final Logger LOGGER = LoggerFactory.getLogger(PromptController.class);

    @Inject
    PromptService service;
    private Validator validator;

    @Inject
    AgentClient agentClient;
    private TranslateService translateService;

    public PromptController() {
        super(null);
    }

    @Inject
    public PromptController(UserService userService, PromptService service, Validator validator, TranslateService translateService) {
        super(userService);
        this.service = service;
        this.validator = validator;
        this.translateService = translateService;
    }

    public void setupRoutes(Router router) {
        String path = "/api/prompts";
        router.route(path + "*").handler(BodyHandler.create());
        router.post(path + "/translate/start").handler(this::translateStart);
        router.get(path + "/translate/stream").handler(this::translateStream);
        router.get(path).handler(this::getAll);
        router.post(path + "/test").handler(this::test);
        router.get(path + "/:id").handler(this::getById);
        router.post(path).handler(this::upsert);
        router.post(path + "/:id").handler(this::upsert);
        router.delete(path + "/:id").handler(this::delete);
        router.get(path + "/:id/access").handler(this::getDocumentAccess);
    }

    private void getAll(RoutingContext rc) {
        int page = Integer.parseInt(rc.request().getParam("page", "1"));
        int size = Integer.parseInt(rc.request().getParam("size", "10"));
        PromptFilterDTO filter = parseFilterDTO(rc);

        getContextUser(rc, false, true)
                .chain(user -> Uni.combine().all().unis(
                        service.getAllCount(user, filter),
                        service.getAll(size, (page - 1) * size, user, filter)
                ).asTuple().map(tuple -> {
                    ViewPage viewPage = new ViewPage();
                    View<PromptDTO> dtoEntries = new View<>(tuple.getItem2(),
                            tuple.getItem1(), page,
                            RuntimeUtil.countMaxPage(tuple.getItem1(), size),
                            size);
                    viewPage.addPayload(PayloadType.VIEW_DATA, dtoEntries);
                    viewPage.addPayload(PayloadType.CONTEXT_ACTIONS, new ActionBox());
                    return viewPage;
                }))
                .subscribe().with(
                        viewPage -> rc.response().setStatusCode(200).end(JsonObject.mapFrom(viewPage).encode()),
                        throwable -> {
                            LOGGER.error("Failed to get all prompts", throwable);
                            rc.fail(throwable);
                        }
                );
    }

    private PromptFilterDTO parseFilterDTO(RoutingContext rc) {
        String filterParam = rc.request().getParam("filter");
        if (filterParam == null || filterParam.isBlank()) {
            return null;
        }

        PromptFilterDTO dto = new PromptFilterDTO();
        boolean any = false;

        try {
            LOGGER.debug("Parsing filter parameter: {}", filterParam);
            JsonObject json = new JsonObject(filterParam);

            if (json.containsKey("languageCode")) {
                dto.setLanguageCode(LanguageCode.valueOf(json.getString("languageCode")));
                any = true;
            }

            if (json.containsKey("promptType")) {
                dto.setPromptType(PromptType.valueOf(json.getString("promptType")));
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
                        PromptDTO dto = new PromptDTO();
                        dto.setPrompt(promptBaseExample);
                        return Uni.createFrom().item(Tuple2.of(dto, user));
                    }
                    return service.getDTO(UUID.fromString(id), user, languageCode)
                            .map(doc -> Tuple2.of(doc, user));
                })
                .subscribe().with(
                        tuple -> {
                            PromptDTO doc = tuple.getItem1();
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

            PromptDTO dto = rc.body().asJsonObject().mapTo(PromptDTO.class);
            String id = rc.pathParam("id");

            Set<ConstraintViolation<PromptDTO>> violations = validator.validate(dto);
            if (violations != null && !violations.isEmpty()) {
                Map<String, List<String>> fieldErrors = new HashMap<>();
                for (ConstraintViolation<PromptDTO> v : violations) {
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

    private void test(RoutingContext rc) {
        try {
            if (!validateJsonBody(rc)) return;

            PromptTestReqDTO dto = rc.body().asJsonObject().mapTo(PromptTestReqDTO.class);
            if (!validateDTO(rc, dto, validator)) return;

            getContextUser(rc, false, true)
                    .chain(user -> agentClient.testPrompt(dto.getPrompt(), dto.getDraft(), dto.getLlmType()))
                    .subscribe().with(
                            response -> rc.response()
                                    .setStatusCode(200)
                                    .putHeader("Content-Type", "application/json")
                                    .end(JsonObject.mapFrom(response).encode()),
                            rc::fail
                    );

        } catch (Exception e) {
            rc.fail(400, new IllegalArgumentException("Invalid request: " + e.getMessage()));
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
            java.util.List<TranslateReqDTO> dtos = new java.util.ArrayList<>();
            for (int i = 0; i < array.size(); i++) {
                JsonObject obj = array.getJsonObject(i);
                dtos.add(obj.mapTo(TranslateReqDTO.class));
            }

            for (TranslateReqDTO dto : dtos) {
                Set<ConstraintViolation<TranslateReqDTO>> violations = validator.validate(dto);
                if (violations != null && !violations.isEmpty()) {
                    Map<String, java.util.List<String>> fieldErrors = new HashMap<>();
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
                        translateService.startJobForPrompts(jobId, dtos, user);
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

    static String promptBaseExample = """
        Rewrite the following draft as a lively DJ introduction.
        
        Rules:
        
        Listener messages and events must always be included — never skip them.
        
        After finishing all listener messages/events, make a smooth transition with short connectors like "Now…", "Let's keep rolling…", "And moving forward…", optionally followed by [pause] or [long pause].
        
        Song title and artist should usually be mentioned, but may be omitted if it makes the flow more natural.
        
        Song description, genres, or history can be blended in briefly when useful, but are optional.
        
        Do not mention the exact time (hours, minutes) — it sounds unnatural in this context.
        
        Atmosphere hints (audience, setting, current vibe) may inspire a short quip or reference, but keep it concise.
        
        Avoid repeating the same introduction speech as in history (see "Last intro speech" if provided).
        
        Self-introductions (DJ name, brand) should be rare — not every track, only once in a while when it adds energy.
        
        Never complain about missing info, never ask for more.
        
        Aim for ≤30 words unless multiple messages/events justify slightly longer.
        
        Always sound like a DJ speaking to the audience: lively, entertaining, and natural.
        
        Audio Tags available (use naturally, not mechanically):
        
        Pauses / pacing: [pause], [long pause], [hesitates], [continues softly]
        
        Emotions / moods: [happy], [excited], [sad], [angry], [nervous], [curious], [mischievous], [serious], [reflective], [dramatic tone], [lighthearted]
        
        Delivery / tone: [whispers], [shouts], [speaking softly], [quietly], [loudly], [rushed], [slows down], [emphasized]
        
        Non-verbal sounds: [laughs], [chuckles], [sighs], [gasp], [gulps], [clears throat], [inhales deeply], [exhales sharply]
        
        Character / style: [French accent], [British accent], [pirate voice], [evil scientist voice], [narrator tone]
        
        Ambient / effects: [applause], [clapping], [door creaks], [explosion], [gunshot]
        
        Note: Use tags sparingly and always mix them with natural connectors — listener messages should flow into lines like "Now…", "Let's keep it going…", "Here we go…" to sound like a real DJ.
        """ ;

}

