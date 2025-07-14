package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.dto.ProfileDTO;
import io.kneo.broadcaster.dto.actions.ProfileActionsFactory;
import io.kneo.broadcaster.model.Profile;
import io.kneo.broadcaster.service.ProfileService;
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
public class ProfileController extends AbstractSecuredController<Profile, ProfileDTO> {

    @Inject
    ProfileService service;
    private Validator validator;

    public ProfileController() {
        super(null);
    }

    @Inject
    public ProfileController(UserService userService, ProfileService service, Validator validator) {
        super(userService);
        this.service = service;
        this.validator = validator;
    }

    public void setupRoutes(Router router) {
        String path = "/api/profiles";
        //router.route().handler(BodyHandler.create());
        router.route(path + "*").handler(BodyHandler.create());
        //router.route(path + "/*").handler(this::addHeaders);
        router.get(path).handler(this::getAll);
        router.get(path + "/:id").handler(this::getById);
        router.post(path).handler(this::upsert);
        router.post(path + "/:id").handler(this::upsert);
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
                    View<ProfileDTO> dtoEntries = new View<>(tuple.getItem2(),
                            tuple.getItem1(), page,
                            RuntimeUtil.countMaxPage(tuple.getItem1(), size),
                            size);
                    viewPage.addPayload(PayloadType.VIEW_DATA, dtoEntries);
                    ActionBox actions = ProfileActionsFactory.getViewActions(user.getActivatedRoles());
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
                        ProfileDTO dto = new ProfileDTO();
                        return Uni.createFrom().item(Tuple2.of(dto, user));
                    }
                    return service.getDTO(UUID.fromString(id), user, languageCode)
                            .map(doc -> Tuple2.of(doc, user));
                })
                .subscribe().with(
                        tuple -> {
                            ProfileDTO profile = tuple.getItem1();
                            FormPage page = new FormPage();
                            page.addPayload(PayloadType.DOC_DATA, profile);
                            page.addPayload(PayloadType.CONTEXT_ACTIONS, new ActionBox());
                            rc.response().setStatusCode(200).end(JsonObject.mapFrom(page).encode());
                        },
                        rc::fail
                );
    }

    private void upsert(RoutingContext rc) {
        try {
            if (!validateJsonBody(rc)) return;

            ProfileDTO dto = rc.body().asJsonObject().mapTo(ProfileDTO.class);
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
}