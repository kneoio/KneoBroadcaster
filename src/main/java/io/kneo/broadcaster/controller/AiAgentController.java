package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.dto.actions.AiAgentActionsFactory;
import io.kneo.broadcaster.dto.ai.AiAgentDTO;
import io.kneo.broadcaster.model.ai.AiAgent;
import io.kneo.broadcaster.service.AiAgentService;
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

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class AiAgentController extends AbstractSecuredController<AiAgent, AiAgentDTO> {

    @Inject
    AiAgentService service;
    private Validator validator;

    public AiAgentController() {
        super(null);
    }

    @Inject
    public AiAgentController(UserService userService, AiAgentService service, Validator validator) {
        super(userService);
        this.service = service;
        this.validator = validator;
    }

    public void setupRoutes(Router router) {
        String path = "/api/aiagents";
        router.route(path + "*").handler(BodyHandler.create());
        router.get(path).handler(this::getAll);
        router.get(path + "/:id").handler(this::getById);
        router.post(path + "/:id?").handler(this::upsert);
        router.delete(path + "/:id").handler(this::delete);
        router.get(path + "/:id/access").handler(this::getDocumentAccess);
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
                    View<AiAgentDTO> dtoEntries = new View<>(tuple.getItem2(),
                            tuple.getItem1(), page,
                            RuntimeUtil.countMaxPage(tuple.getItem1(), size),
                            size);
                    viewPage.addPayload(PayloadType.VIEW_DATA, dtoEntries);
                    ActionBox actions = AiAgentActionsFactory.getViewActions(user.getActivatedRoles());
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

        getContextUser(rc, false, true)
                .chain(user -> {
                    if ("new".equals(id)) {
                        AiAgentDTO dto = new AiAgentDTO();
                       /** VoiceDTO voice1 = new VoiceDTO();
                        voice1.setId("nPczCjzI2devNBz1zQrb");
                        voice1.setName("Brain");
                        VoiceDTO voice2 = new VoiceDTO();
                        voice2.setId("CwhRBWXzGAHq8TQ4Fs17");
                        voice2.setName("Roger");
                        dto.setPreferredVoice(List.of(voice1, voice2));
                        ToolDTO tool1 = new ToolDTO();
                        tool1.setName("song title");
                        tool1.setDescription("Represent song title");
                        tool1.setVariableName("song_title");
                        ToolDTO tool2 = new ToolDTO();
                        tool2.setName("artist");
                        tool2.setDescription("Represent artist");
                        tool2.setVariableName("artist");
                        ToolDTO tool3 = new ToolDTO();
                        tool3.setName("listeners");
                        tool3.setDescription("Listeners of the radio station");
                        tool3.setVariableName("listeners");
                        ToolDTO tool4 = new ToolDTO();
                        tool4.setName("context");
                        tool4.setDescription("Context of the audience");
                        tool4.setVariableName("context");
                        ToolDTO tool5 = new ToolDTO();
                        tool5.setName("history");
                        tool5.setDescription("History of the previous announcements");
                        tool5.setVariableName("history");
                        ToolDTO tool6 = new ToolDTO();
                        tool6.setName("messages");
                        tool6.setDescription("Can catch a message from Mixpla");
                        tool6.setVariableName("message");
                        dto.setEnabledTools(List.of(tool1, tool2, tool3, tool4, tool5, tool6));**/
                        dto.setTalkativity(0.3);
                        dto.setFillerPrompt(DEFAULT_FILLER_PROMPTS);
                        dto.setPrompts(List.of(PROMPT_BASIC));
                        return Uni.createFrom().item(Tuple2.of(dto, user));
                    }
                    return service.getDTO(UUID.fromString(id), user, languageCode)
                            .map(doc -> Tuple2.of(doc, user));
                })
                .subscribe().with(
                        tuple -> {
                            AiAgentDTO owner = tuple.getItem1();
                            FormPage page = new FormPage();
                            page.addPayload(PayloadType.DOC_DATA, owner);
                            page.addPayload(PayloadType.CONTEXT_ACTIONS, new ActionBox());
                            rc.response().setStatusCode(200).end(JsonObject.mapFrom(page).encode());
                        },
                        rc::fail
                );
    }

    private void upsert(RoutingContext rc) {
        try {
            if (!validateJsonBody(rc)) return;

            AiAgentDTO dto = rc.body().asJsonObject().mapTo(AiAgentDTO.class);
            String id = rc.pathParam("id");

            if (!validateDTO(rc, dto, validator)) return;

            getContextUser(rc, false, true)
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
        getContextUser(rc, false, true)
                .chain(user -> service.delete(id, user))
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

    public static final List<String> DEFAULT_FILLER_PROMPTS = List.of(
            "Eerie mood music with smooth transition, 4-6 seconds",
            "Eerie ambient music with gentle fade out, 4-6 seconds",
            "Huge epic braam with natural decay, 4-6 seconds",
            "Deep epic braam with gradual fade, 4-6 seconds",
            "Massive cinematic braam with soft ending, 4-6 seconds"
    );

    private  static final String PROMPT_BASIC = """
            You are a radio DJ called {ai_dj_name} of radio station named {brand}. Introduce {song_title} by {artist} to our audience,
             including listeners like {listeners}. Factor in the current context: {context}. Important
             constraint: Keep introduction extremely concise (10-30 words) - longer introductions cannot be used.
            Your introduction should connect naturally with previous interactions. Previous interactions context: {history}.
             Priority instruction: Check the user message: {instant_message}. If this field contains meaningful content,
              incorporate it as the primary focus of your introduction.
             Events handling: Check events: {events} for special events. If "The shift of the dj started" is present,
              greet the listeners and introduce yourself - in this case, ignore the word limit.
              If "The shift of the dj ended" is present, say warm goodbye to the listeners before introducing
               the song - in this case, ignore the word limit.
            """;
}