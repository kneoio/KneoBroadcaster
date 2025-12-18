package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.dto.actions.SoundFragmentActionsFactory;
import io.kneo.broadcaster.dto.radiostation.BrandDTO;
import io.kneo.broadcaster.model.brand.Brand;
import io.kneo.broadcaster.model.cnst.ManagedBy;
import io.kneo.broadcaster.model.stream.IStream;
import io.kneo.broadcaster.service.BrandService;
import io.kneo.broadcaster.service.stream.RadioStationPool;
import io.kneo.broadcaster.service.stream.StreamScheduleService;
import io.kneo.broadcaster.util.ProblemDetailsUtil;
import io.kneo.core.controller.AbstractSecuredController;
import io.kneo.core.dto.actions.ActionBox;
import io.kneo.core.dto.cnst.PayloadType;
import io.kneo.core.dto.form.FormPage;
import io.kneo.core.dto.view.View;
import io.kneo.core.dto.view.ViewPage;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.AnonymousUser;
import io.kneo.core.service.UserService;
import io.kneo.core.util.RuntimeUtil;
import io.kneo.core.util.WebHelper;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.tuples.Tuple2;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class BrandController extends AbstractSecuredController<Brand, BrandDTO> {
    private static final Logger LOGGER = LoggerFactory.getLogger(BrandController.class);

    private BrandService service;
    private Validator validator;
    private RadioStationPool radioStationPool;
    private StreamScheduleService streamScheduleService;

    public BrandController() {
        super(null);
    }

    @Inject
    public BrandController(UserService userService, BrandService service, Validator validator,
                           RadioStationPool radioStationPool, StreamScheduleService streamScheduleService) {
        super(userService);
        this.service = service;
        this.validator = validator;
        this.radioStationPool = radioStationPool;
        this.streamScheduleService = streamScheduleService;
    }

    public void setupRoutes(Router router) {
        String path = "/api/radiostations";
        router.route(path + "*").handler(BodyHandler.create());
        router.get(path).handler(this::getAll);
        router.get(path + "/:id").handler(this::getById);
        router.post(path + "/:id?").handler(this::upsert);
        router.delete(path + "/:id").handler(this::delete);
        router.get(path + "/:id/access").handler(this::getDocumentAccess);
        router.post(path + "/:slugName/schedule").handler(this::buildSchedule);
    }

    private void getAll(RoutingContext rc) {
        int page = Integer.parseInt(rc.request().getParam("page", "1"));
        int size = Integer.parseInt(rc.request().getParam("size", "10"));

        getContextUser(rc, false, true)
                .chain(user -> Uni.combine().all().unis(
                        service.getAllCount(user),
                        service.getAllDTO(size, (page - 1) * size, user)
                ).asTuple().map(tuple -> {
                    ViewPage viewPage = new ViewPage();
                    View<BrandDTO> dtoEntries = new View<>(tuple.getItem2(),
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

        getContextUser(rc, false, true)
                .chain(user -> {
                    if ("new".equals(id)) {
                        BrandDTO dto = new BrandDTO();
                        dto.setLocalizedName(new EnumMap<>(LanguageCode.class));
                        dto.getLocalizedName().put(LanguageCode.en, "");
                        dto.setManagedBy(ManagedBy.MIX);
                        dto.setColor(WebHelper.generateRandomBrightColor());
                        dto.setBitRate(128000);
                        return Uni.createFrom().item(Tuple2.of(dto, user));
                    }
                    return service.getDTO(UUID.fromString(id), user, languageCode)
                            .map(doc -> Tuple2.of(doc, user));
                })
                .subscribe().with(
                        tuple -> {
                            BrandDTO doc = tuple.getItem1();
                            FormPage page = new FormPage();
                            page.addPayload(PayloadType.DOC_DATA, doc);
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
        try {
            if (!validateJsonBody(rc)) {
                return;
            }

            String id = rc.pathParam("id");
            BrandDTO dto = rc.body().asJsonObject().mapTo(BrandDTO.class);

            Set<jakarta.validation.ConstraintViolation<BrandDTO>> violations = validator.validate(dto);
            if (violations != null && !violations.isEmpty()) {
                Map<String, List<String>> fieldErrors = new HashMap<>();
                for (jakarta.validation.ConstraintViolation<BrandDTO> v : violations) {
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
                            throwable -> {
                                LOGGER.error("Failed to upsert radio station with id: {}", id, throwable);
                                rc.fail(throwable);
                            }
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
                .chain(user -> service.archive(id, user))
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

    private void buildSchedule(RoutingContext rc) {
        String slugName = rc.pathParam("slugName");

        getContextUser(rc, false, true)
                .chain(user -> {
                    IStream stream = radioStationPool.getStation(slugName);
                    if (stream == null) {
                        return Uni.createFrom().failure(new IllegalArgumentException("Stream not found in pool: " + slugName));
                    }

                    if (stream.getStreamSchedule() != null) {
                        LOGGER.info("Stream '{}' already has a schedule, using existing", slugName);
                        return Uni.createFrom().item(stream.getStreamSchedule());
                    }

                    return streamScheduleService.buildStreamSchedule(stream.getMasterBrand().getId(), UUID.randomUUID(), AnonymousUser.build())
                            .invoke(stream::setStreamSchedule);
                })
                .subscribe().with(
                        schedule -> {
                            JsonObject response = new JsonObject();
                            response.put("slugName", slugName);
                            response.put("totalScenes", schedule != null ? schedule.getTotalScenes() : 0);
                            response.put("totalSongs", schedule != null ? schedule.getTotalSongs() : 0);
                            response.put("estimatedEndTime", schedule != null ? schedule.getEstimatedEndTime().toString() : null);
                            rc.response()
                                    .setStatusCode(200)
                                    .putHeader("Content-Type", "application/json")
                                    .end(response.encode());
                        },
                        throwable -> {
                            LOGGER.error("Failed to build schedule for stream: {}", slugName, throwable);
                            if (throwable instanceof IllegalArgumentException) {
                                rc.fail(400, throwable);
                            } else {
                                rc.fail(500, throwable);
                            }
                        }
                );
    }
}