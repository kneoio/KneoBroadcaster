package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.dto.RadioStationDTO;
import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.service.RadioStationService;
import io.kneo.core.controller.AbstractSecuredController;
import io.kneo.core.dto.actions.ActionBox;
import io.kneo.core.dto.cnst.PayloadType;
import io.kneo.core.dto.form.FormPage;
import io.kneo.core.localization.LanguageCode;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.UUID;

@ApplicationScoped
public class RadioStationController extends AbstractSecuredController<RadioStation, RadioStationDTO> {

    @Inject
    RadioStationService service;

    public RadioStationController() {
        super(null);
    }

    public void setupRoutes(Router router) {
        String basePath = "/api/:brand/radiostations";
        router.route(basePath + "*").handler(BodyHandler.create());
        router.get(basePath).handler(this::getAll);
        router.get(basePath + "/:id").handler(this::getById);
        router.route(HttpMethod.POST, basePath + "/:id?").handler(this::upsert);
        router.delete(basePath + "/:id").handler(this::delete);
    }

    private void getAll(RoutingContext rc) {
        int page = Integer.parseInt(rc.request().getParam("page", "1"));
        int size = Integer.parseInt(rc.request().getParam("size", "10"));
        service.getAll(size, (page - 1) * size)
                .subscribe().with(
                        list -> rc.response().setStatusCode(200).end(Json.encodePrettily(list)),
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
        JsonObject jsonObject = rc.body().asJsonObject();
        RadioStationDTO dto = jsonObject.mapTo(RadioStationDTO.class);

        getContextUser(rc)
                .chain(user -> service.upsert(id, dto, user, LanguageCode.ENG))
                .subscribe().with(
                        doc -> rc.response().setStatusCode(id == null ? 201 : 200).end(JsonObject.mapFrom(doc).encode()),
                        rc::fail
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
}
