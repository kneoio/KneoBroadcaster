package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.dto.radiostation.BuildScheduleReqDTO;
import io.kneo.broadcaster.dto.radiostation.OneTimeStreamRunReqDTO;
import io.kneo.broadcaster.dto.stream.OneTimeStreamDTO;
import io.kneo.broadcaster.model.stream.IStream;
import io.kneo.broadcaster.service.OneTimeStreamService;
import io.kneo.broadcaster.service.stream.StreamScheduleService;
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
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class StreamController extends AbstractSecuredController<IStream, OneTimeStreamDTO> {
    private static final Logger LOGGER = LoggerFactory.getLogger(StreamController.class);

    private OneTimeStreamService oneTimeStreamService;
    private StreamScheduleService streamScheduleService;
    private Validator validator;

    public StreamController() {
        super(null);
    }

    @Inject
    public StreamController(UserService userService, OneTimeStreamService oneTimeStreamService, StreamScheduleService streamScheduleService, Validator validator) {
        super(userService);
        this.oneTimeStreamService = oneTimeStreamService;
        this.streamScheduleService = streamScheduleService;
        this.validator = validator;
    }

    public void setupRoutes(Router router) {
        String path = "/api/streams";
        router.route(path + "/*").handler(BodyHandler.create());
        router.get(path).handler(this::getAll);
        router.post(path + "/schedule").handler(this::buildSchedule);
        router.post(path + "/run").handler(this::runOneTimeStream);
        router.get(path + "/:id").handler(this::getById);
        router.post(path).handler(this::upsert);
        router.post(path + "/:id").handler(this::upsert);
        router.delete(path + "/:id").handler(this::delete);
    }

    private void getAll(RoutingContext rc) {
        int page = Integer.parseInt(rc.request().getParam("page", "1"));
        int size = Integer.parseInt(rc.request().getParam("size", "10"));

        Uni.combine().all().unis(
                        oneTimeStreamService.getAllCount(),
                        oneTimeStreamService.getAll(size, (page - 1) * size)
                ).asTuple()
                .subscribe().with(
                        tuple -> {
                            int total = tuple.getItem1();
                            List<OneTimeStreamDTO> dtos = tuple.getItem2();

                            ViewPage viewPage = new ViewPage();
                            View<OneTimeStreamDTO> dtoEntries = new View<>(dtos,
                                    total, page,
                                    RuntimeUtil.countMaxPage(total, size),
                                    size);
                            viewPage.addPayload(PayloadType.VIEW_DATA, dtoEntries);
                            rc.response().setStatusCode(200).end(JsonObject.mapFrom(viewPage).encode());
                        },
                        throwable -> {
                            LOGGER.error("Failed to get all streams", throwable);
                            rc.fail(throwable);
                        }
                );
    }

    private void delete(RoutingContext rc) {
        String id = rc.pathParam("id");
        oneTimeStreamService.delete(UUID.fromString(id))
                .subscribe().with(
                        item -> rc.response().setStatusCode(204).end(),
                        throwable -> {
                            LOGGER.error("Failed to delete stream by id: {}", id, throwable);
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
                        OneTimeStreamDTO dto = new OneTimeStreamDTO();
                        dto.setLocalizedName(new EnumMap<>(LanguageCode.class));
                        dto.getLocalizedName().put(LanguageCode.en, "");
                        dto.setBitRate(128000);
                        return Uni.createFrom().item(Tuple2.of(dto, user));
                    }
                    return oneTimeStreamService.getDTO(UUID.fromString(id), user, languageCode)
                            .map(doc -> Tuple2.of(doc, user));
                })
                .subscribe().with(
                        tuple -> {
                            OneTimeStreamDTO doc = tuple.getItem1();
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
            if (!validateJsonBody(rc)) return;

            OneTimeStreamDTO dto = rc.body().asJsonObject().mapTo(OneTimeStreamDTO.class);
            String id = rc.pathParam("id");

            Set<ConstraintViolation<OneTimeStreamDTO>> violations = validator.validate(dto);
            if (violations != null && !violations.isEmpty()) {
                Map<String, List<String>> fieldErrors = new HashMap<>();
                for (ConstraintViolation<OneTimeStreamDTO> v : violations) {
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
                    .chain(user -> oneTimeStreamService.upsert(id, dto, user, LanguageCode.en))
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

    private void buildSchedule(RoutingContext rc) {
        if (!validateJsonBody(rc)) {
            return;
        }

        BuildScheduleReqDTO dto = rc.body().asJsonObject().mapTo(BuildScheduleReqDTO.class);

        getContextUser(rc, false, true)
                .chain(user -> streamScheduleService.getStreamScheduleDTO(dto.getBaseBrandId(), dto.getScriptId(), user))
                .subscribe().with(
                        result -> {
                            FormPage page = new FormPage();
                            page.addPayload(PayloadType.DOC_DATA, result);
                            page.addPayload(PayloadType.CONTEXT_ACTIONS, new ActionBox());
                            rc.response()
                                    .putHeader("Content-Type", "application/json")
                                    .setStatusCode(200)
                                    .end(JsonObject.mapFrom(page).encode());
                        },
                        throwable -> {
                            LOGGER.error("Failed to build schedule for brandId: {}, scriptId: {}", dto.getBaseBrandId(), dto.getScriptId(), throwable);
                            rc.fail(throwable);
                        }
                );
    }

    private void runOneTimeStream(RoutingContext rc) {
        try {
            if (!validateJsonBody(rc)) {
                return;
            }

            OneTimeStreamRunReqDTO dto = rc.body().asJsonObject().mapTo(OneTimeStreamRunReqDTO.class);
            LanguageCode languageCode = LanguageCode.valueOf(rc.request().getParam("lang", LanguageCode.en.name()));

            Set<ConstraintViolation<OneTimeStreamRunReqDTO>> violations = validator.validate(dto);
            if (violations != null && !violations.isEmpty()) {
                Map<String, List<String>> fieldErrors = new HashMap<>();
                for (ConstraintViolation<OneTimeStreamRunReqDTO> v : violations) {
                    String field = v.getPropertyPath().toString();
                    fieldErrors.computeIfAbsent(field, k -> new ArrayList<>()).add(v.getMessage());
                }

                String detail = fieldErrors.entrySet().stream()
                        .flatMap(e -> e.getValue().stream().map(msg -> e.getKey() + ": " + msg))
                        .collect(Collectors.joining(", "));

                rc.response()
                        .setStatusCode(400)
                        .putHeader("Content-Type", "application/json")
                        .end(new JsonObject()
                                .put("error", "Validation failed")
                                .put("detail", detail)
                                .encode());
                return;
            }

            getContextUser(rc, false, true)
                    .chain(user -> oneTimeStreamService.run(dto, user))
                    .subscribe().with(
                            stream -> {
                                JsonObject response = new JsonObject()
                                        .put("id", stream.getId().toString())
                                        .put("slugName", stream.getSlugName());
                                rc.response()
                                        .putHeader("Content-Type", "application/json")
                                        .setStatusCode(200)
                                        .end(response.encode());
                            },
                            throwable -> {
                                LOGGER.error("Failed to run one-time stream", throwable);
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
}
