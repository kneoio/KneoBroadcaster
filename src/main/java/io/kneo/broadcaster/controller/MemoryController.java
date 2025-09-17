package io.kneo.broadcaster.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kneo.broadcaster.dto.memory.EventDTO;
import io.kneo.broadcaster.dto.memory.IMemoryContentDTO;
import io.kneo.broadcaster.dto.memory.MemoryDTO;
import io.kneo.broadcaster.dto.memory.MessageDTO;
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
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class MemoryController extends AbstractSecuredController<Object, MemoryDTO> {

    private MemoryService service;

    @Inject
    ObjectMapper mapper;

    @Inject
    Validator validator;

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

    private void getById(RoutingContext rc) {
        String id = rc.pathParam("id");

        getContextUser(rc)
                .chain(user -> {
                    return service.getDTO(UUID.fromString(id), user, resolveLanguage(rc))
                            .map(doc -> Tuple2.of(doc, user));
                })
                .subscribe().with(
                        tuple -> {
                            MemoryDTO doc = tuple.getItem1();
                            FormPage page = new FormPage();
                            page.addPayload(PayloadType.DOC_DATA, serializeMemoryDTO(doc));
                            page.addPayload(PayloadType.CONTEXT_ACTIONS, new ActionBox());
                            rc.response().setStatusCode(200).end(JsonObject.mapFrom(page).encode());
                        },
                        rc::fail
                );
    }

    private void upsert(RoutingContext rc) {
        String id = rc.pathParam("id");
        JsonObject jsonObject = rc.body().asJsonObject();
        MemoryDTO dto = deserializeMemoryDTO(jsonObject, rc);
        if (dto == null) return; // Error already handled

        getContextUser(rc)
                .chain(user -> service.upsert(id, dto, user))
                .subscribe().with(
                        doc -> rc.response().setStatusCode(id == null ? 201 : 200).end(serializeMemoryDTO(doc).encode()),
                        rc::fail
                );
    }

    private MemoryDTO deserializeMemoryDTO(JsonObject jsonObject, RoutingContext rc) {
        try {
            String memoryTypeStr = jsonObject.getString("memoryType");
            if (memoryTypeStr == null) {
                rc.fail(new IllegalArgumentException("memoryType is required"));
                return null;
            }

            String jsonString = jsonObject.encode();
            MemoryDTO dto = mapper.readValue(jsonString, MemoryDTO.class);

            JsonArray contentArray = jsonObject.getJsonArray("content");
            if (contentArray != null) {
                List<IMemoryContentDTO> contentList = new ArrayList<>();

                if (memoryTypeStr.equals(MemoryType.EVENT.name())) {
                    for (int i = 0; i < contentArray.size(); i++) {
                        JsonObject contentObj = contentArray.getJsonObject(i);
                        EventDTO eventDTO = mapper.readValue(contentObj.encode(), EventDTO.class);
                        Set<ConstraintViolation<EventDTO>> violations = validator.validate(eventDTO);
                        if (!violations.isEmpty()) {
                            String errors = violations.stream()
                                    .map(ConstraintViolation::getMessage)
                                    .collect(Collectors.joining(", "));
                            rc.fail(new IllegalArgumentException("Event validation failed: " + errors));
                            return null;
                        }

                        contentList.add(eventDTO);
                    }
                } else if (memoryTypeStr.equals(MemoryType.MESSAGE.name())) {
                    for (int i = 0; i < contentArray.size(); i++) {
                        JsonObject contentObj = contentArray.getJsonObject(i);
                        MessageDTO messageDTO = mapper.readValue(contentObj.encode(), MessageDTO.class);
                        Set<ConstraintViolation<MessageDTO>> violations = validator.validate(messageDTO);
                        if (!violations.isEmpty()) {
                            String errors = violations.stream()
                                    .map(ConstraintViolation::getMessage)
                                    .collect(Collectors.joining(", "));
                            rc.fail(new IllegalArgumentException("Message validation failed: " + errors));
                            return null;
                        }
                        contentList.add(messageDTO);
                    }
                }
                dto.setContent(contentList);
            }
            return dto;
        } catch (Exception e) {
            rc.fail(e);
            return null;
        }
    }

    private JsonObject serializeMemoryDTO(MemoryDTO dto) {
        JsonObject json = JsonObject.mapFrom(dto);
        if (dto.getContent() != null) {
            JsonArray contentArray = new JsonArray();
            dto.getContent().forEach(item -> contentArray.add(JsonObject.mapFrom(item)));
            json.put("content", contentArray);
        }
        return json;
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