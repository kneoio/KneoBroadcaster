package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.dto.MemoryDTO;
import io.kneo.broadcaster.model.Memory;
import io.kneo.broadcaster.service.MemoryService;
import io.kneo.core.controller.AbstractSecuredController;
import io.kneo.core.dto.cnst.PayloadType;
import io.kneo.core.dto.view.View;
import io.kneo.core.dto.view.ViewPage;
import io.kneo.core.model.user.SuperUser;
import io.kneo.core.service.UserService;
import io.kneo.core.util.RuntimeUtil;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.UUID;

@ApplicationScoped
public class MemoryController  extends AbstractSecuredController<Memory, MemoryDTO> {

    private MemoryService service;

    public MemoryController() {
        super(null);
    }

    @Inject
    public MemoryController(UserService userService, MemoryService service) {
        super(userService);
        this.service = service;
    }

    public void setupRoutes(Router router) {
        router.route("/api/*").handler(BodyHandler.create());
        router.get("/api/memories").handler(this::getAll);
        router.post("/api/memories/:id?").handler(this::upsert);
        router.delete("/api/memories/:id").handler(this::delete);
        router.delete("/api/memories/brand/:brand").handler(this::deleteMemoriesByBrand);
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
                    View<MemoryDTO> dtoEntries = new View<>(tuple.getItem2(),
                            tuple.getItem1(), page,
                            RuntimeUtil.countMaxPage(tuple.getItem1(), size),
                            size);
                    viewPage.addPayload(PayloadType.VIEW_DATA, dtoEntries);
                    return viewPage;
                }))
                .subscribe().with(
                        viewPage -> rc.response().setStatusCode(200).end(JsonObject.mapFrom(viewPage).encode()),
                        rc::fail
                );
    }


    private void upsert(RoutingContext rc) {
        String idParam = rc.pathParam("id");
        UUID id = null;
        boolean isUpdate = idParam != null;

        try {
            if (isUpdate) {
                id = UUID.fromString(idParam);
            }

            JsonObject jsonObject = rc.body().asJsonObject();
            if (jsonObject == null) {
                rc.response().setStatusCode(400).putHeader("Content-Type", "text/plain").end("Request body cannot be empty.");
                return;
            }
            MemoryDTO dto = jsonObject.mapTo(MemoryDTO.class);

            Uni<MemoryDTO> upsertOperation = service.upsert(id, dto, SuperUser.build());

            upsertOperation.subscribe().with(
                    savedMemory -> rc.response()
                            .setStatusCode(isUpdate ? 200 : 201)
                            .putHeader("Content-Type", "application/json")
                            .end(Json.encode(savedMemory)),
                    failure -> rc.response()
                            .setStatusCode(400)
                            .putHeader("Content-Type", "text/plain")
                            .end(failure.getMessage())
            );

        } catch (IllegalArgumentException e) {
            rc.response()
                    .setStatusCode(400)
                    .putHeader("Content-Type", "text/plain")
                    .end("Invalid ID format. Must be a valid UUID.");
        } catch (Exception e) {
            rc.response()
                    .setStatusCode(400)
                    .putHeader("Content-Type", "text/plain")
                    .end("Invalid request data: " + e.getMessage());
        }
    }

    private void deleteMemoriesByBrand(RoutingContext rc) {
        try {
            String brand = rc.pathParam("brand");

            service.deleteByBrand(brand)
                    .subscribe().with(
                            deletedCount -> {
                                JsonObject response = new JsonObject()
                                        .put("deletedCount", deletedCount)
                                        .put("brand", brand);
                                rc.response()
                                        .setStatusCode(200)
                                        .putHeader("Content-Type", "application/json")
                                        .end(response.encode());
                            },
                            failure -> rc.response()
                                    .setStatusCode(400)
                                    .putHeader("Content-Type", "text/plain")
                                    .end(failure.getMessage())
                    );
        } catch (Exception e) {
            rc.response()
                    .setStatusCode(500)
                    .putHeader("Content-Type", "text/plain")
                    .end("An unexpected error occurred while deleting memories by brand: " + e.getMessage());
        }
    }

    private void delete(RoutingContext rc) {
        String idParam = rc.pathParam("id");
        try {
            UUID id = UUID.fromString(idParam);
            service.delete(id)
                    .subscribe().with(
                            deleted -> rc.response()
                                    .setStatusCode(204)
                                    .end(),
                            failure -> rc.response()
                                    .setStatusCode(400)
                                    .putHeader("Content-Type", "text/plain")
                                    .end(failure.getMessage())
                    );
        } catch (IllegalArgumentException e) {
            rc.response()
                    .setStatusCode(400)
                    .putHeader("Content-Type", "text/plain")
                    .end("Invalid UUID format");
        }
    }
}