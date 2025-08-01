package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.dto.memory.MemoryDTO;
import io.kneo.broadcaster.model.cnst.MemoryType;
import io.kneo.broadcaster.service.MemoryService;
import io.kneo.core.controller.AbstractSecuredController;
import io.kneo.core.dto.actions.ActionBox;
import io.kneo.core.dto.cnst.PayloadType;
import io.kneo.core.dto.form.FormPage;
import io.kneo.core.dto.view.View;
import io.kneo.core.dto.view.ViewPage;
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

import java.util.UUID;

@ApplicationScoped
public class MemoryController extends AbstractSecuredController<Object, MemoryDTO<?>> {

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
        router.get("/api/memories/:id").handler(this::getById);
        router.post("/api/memories/:id?").handler(this::upsert);
        router.delete("/api/memories/:id").handler(this::delete);
        router.delete("/api/memories/brand/:brand").handler(this::deleteByBrand);
        router.post("/api/memories/events").handler(this::triggerEvent);
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
                    View<MemoryDTO<?>> dtoEntries = new View<>(tuple.getItem2(),
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

    private void getById(RoutingContext rc) {
        String id = rc.pathParam("id");

        getContextUser(rc)
                .chain(user -> {
                    return service.getDTO(UUID.fromString(id), user, resolveLanguage(rc))
                            .map(doc -> Tuple2.of(doc, user));
                })
                .subscribe().with(
                        tuple -> {
                            MemoryDTO<?> doc = tuple.getItem1();
                            FormPage page = new FormPage();
                            page.addPayload(PayloadType.DOC_DATA, doc);
                            page.addPayload(PayloadType.CONTEXT_ACTIONS, new ActionBox());
                            rc.response().setStatusCode(200).end(JsonObject.mapFrom(page).encode());
                        },
                        rc::fail
                );
    }

    private void upsert(RoutingContext rc) {
        String id = rc.pathParam("id");
        JsonObject jsonObject = rc.body().asJsonObject();
        MemoryDTO<?> dto = jsonObject.mapTo(MemoryDTO.class);

        getContextUser(rc)
                .chain(user -> service.upsert(id, dto, user))
                .subscribe().with(
                        doc -> rc.response().setStatusCode(id == null ? 201 : 200).end(JsonObject.mapFrom(doc).encode()),
                        rc::fail
                );
    }

    private void triggerEvent(RoutingContext rc) {
        JsonObject jsonObject = rc.body().asJsonObject();
        String brand = jsonObject.getString("brand");
        String memoryTypeStr = jsonObject.getString("memoryType");
        Object content = jsonObject.getValue("content");

        if (brand == null || content == null || memoryTypeStr == null) {
            rc.response().setStatusCode(400).end("{\"error\":\"Brand, content and memoryType are required\"}");
            return;
        }

        MemoryType memoryType;
        try {
            memoryType = MemoryType.valueOf(memoryTypeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            rc.response().setStatusCode(400).end("{\"error\":\"Invalid memory type: " + memoryTypeStr + "\"}");
            return;
        }

        getContextUser(rc)
                .chain(user -> service.upsert(brand, memoryType, content))
                .subscribe().with(
                        id -> rc.response().setStatusCode(201).end("{\"id\":\"" + id + "\"}"),
                        rc::fail
                );
    }

    private void deleteByBrand(RoutingContext rc) {
        String brand = rc.pathParam("brand");
        getContextUser(rc)
                .chain(user -> service.deleteByBrand(brand))
                .subscribe().with(
                        count -> rc.response().setStatusCode(count > 0 ? 204 : 404).end(),
                        rc::fail
                );
    }

    private void delete(RoutingContext rc) {
        String id = rc.pathParam("id");
        getContextUser(rc)
                .chain(user -> service.delete(id))
                .subscribe().with(
                        count -> rc.response().setStatusCode(count > 0 ? 204 : 404).end(),
                        rc::fail
                );
    }
}