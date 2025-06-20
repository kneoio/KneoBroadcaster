package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.dto.RadioStationDTO;
import io.kneo.broadcaster.dto.actions.SoundFragmentActionsFactory;
import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.service.RadioStationService;
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
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

@ApplicationScoped
public class RadioStationController extends AbstractSecuredController<RadioStation, RadioStationDTO> {
    private static final Logger LOGGER = LoggerFactory.getLogger(RadioStationController.class);

    @Inject
    RadioStationService service;

    public RadioStationController() {
        super(null);
    }

    @Inject
    public RadioStationController(UserService userService, RadioStationService service) {
        super(userService);
        this.service = service;
    }

    public void setupRoutes(Router router) {
        String basePath = "/api/radiostations";
        router.route(basePath + "*").handler(BodyHandler.create());
        router.get(basePath).handler(this::getAll);
        router.get(basePath + "/:id").handler(this::getById);
        router.route(HttpMethod.POST, basePath + "/:id?").handler(this::upsert);
        router.delete(basePath + "/:id").handler(this::delete);
    }

    private void getAll(RoutingContext rc) {
        int page = Integer.parseInt(rc.request().getParam("page", "1"));
        int size = Integer.parseInt(rc.request().getParam("size", "10"));

        getContextUser(rc)
                .chain(user -> Uni.combine().all().unis(
                        service.getAllCount(user),
                        service.getAll(size, (page - 1) * size, user)
                ).asTuple().map(tuple -> {
                    ViewPage viewPage = new ViewPage();
                    View<RadioStationDTO> dtoEntries = new View<>(tuple.getItem2(),
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
                        throwable -> {
                            LOGGER.error("Failed to get all radio stations", throwable);
                            rc.fail(throwable);
                        }
                );
    }

    private void getById(RoutingContext rc) {
        String id = rc.pathParam("id");
        LanguageCode languageCode = LanguageCode.valueOf(rc.request().getParam("lang", LanguageCode.en.name()));

        getContextUser(rc)
                .chain(user -> service.getDTO(UUID.fromString(id), user, languageCode))
                .subscribe().with(
                        owner -> {
                            FormPage page = new FormPage();
                            page.addPayload(PayloadType.DOC_DATA, owner);
                            page.addPayload(PayloadType.CONTEXT_ACTIONS, new ActionBox());
                            rc.response().setStatusCode(200).end(JsonObject.mapFrom(page).encode());
                        },
                        throwable -> {
                            LOGGER.error("Failed to get radio station by id: {}", id, throwable);
                            rc.fail(throwable);
                        }
                );
    }

    private void upsert(RoutingContext rc) {
        String id = rc.pathParam("id");
        JsonObject jsonObject = rc.body().asJsonObject();
        RadioStationDTO dto = jsonObject.mapTo(RadioStationDTO.class);

        getContextUser(rc)
                .chain(user -> service.upsert(id, dto, user, LanguageCode.en))
                .subscribe().with(
                        doc -> rc.response().setStatusCode(id == null ? 201 : 200).end(JsonObject.mapFrom(doc).encode()),
                        throwable -> {
                            LOGGER.error("Failed to upsert radio station with id: {}", id, throwable);
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
                        throwable -> {
                            LOGGER.error("Failed to delete radio station with id: {}", id, throwable);
                            rc.fail(throwable);
                        }
                );
    }
}