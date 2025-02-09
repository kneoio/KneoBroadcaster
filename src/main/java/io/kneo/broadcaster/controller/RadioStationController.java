package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.service.RadioStationService;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.UUID;

@ApplicationScoped
public class RadioStationController {

    @Inject
    RadioStationService service;

    public void setupRoutes(Router router) {
        String basePath = "/api/:brand/radiostations";
        router.route(basePath + "*").handler(BodyHandler.create());
        router.get(basePath).handler(this::getAll);
        router.get(basePath + "/:id").handler(this::getById);
        router.post(basePath).handler(this::create);
        router.put(basePath + "/:id").handler(this::update);
        router.delete(basePath + "/:id").handler(this::delete);
    }

    private void getAll(RoutingContext rc) {
        int page = Integer.parseInt(rc.request().getParam("page", "1"));
        int size = Integer.parseInt(rc.request().getParam("size", "10"));
        service.getAllRadioStations(size, (page - 1) * size)
                .subscribe().with(
                        list -> rc.response().setStatusCode(200).end(Json.encodePrettily(list)),
                        rc::fail
                );
    }

    private void getById(RoutingContext rc) {
        try {
            UUID id = UUID.fromString(rc.pathParam("id"));
            service.getRadioStation(id)
                    .subscribe().with(
                            station -> rc.response().setStatusCode(200).end(Json.encodePrettily(station)),
                            rc::fail
                    );
        } catch (IllegalArgumentException e) {
            rc.fail(400);
        }
    }

    private void create(RoutingContext rc) {
        JsonObject body = rc.getBodyAsJson();
        RadioStation station = body.mapTo(RadioStation.class);
        service.createRadioStation(station)
                .subscribe().with(
                        created -> rc.response().setStatusCode(201).end(Json.encodePrettily(created)),
                        rc::fail
                );
    }

    private void update(RoutingContext rc) {
        try {
            UUID id = UUID.fromString(rc.pathParam("id"));
            JsonObject body = rc.getBodyAsJson();
            RadioStation station = body.mapTo(RadioStation.class);
            service.updateRadioStation(id, station)
                    .subscribe().with(
                            updated -> rc.response().setStatusCode(200).end(Json.encodePrettily(updated)),
                            rc::fail
                    );
        } catch (IllegalArgumentException e) {
            rc.fail(400);
        }
    }

    private void delete(RoutingContext rc) {
        try {
            UUID id = UUID.fromString(rc.pathParam("id"));
            service.deleteRadioStation(id)
                    .subscribe().with(
                            count -> rc.response().setStatusCode(count > 0 ? 204 : 404).end(),
                            rc::fail
                    );
        } catch (IllegalArgumentException e) {
            rc.fail(400);
        }
    }
}
