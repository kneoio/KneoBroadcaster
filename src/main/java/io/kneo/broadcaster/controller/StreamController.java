package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.dto.radiostation.OneTimeStreamRunReqDTO;
import io.kneo.broadcaster.dto.stream.OneTimeStreamDTO;
import io.kneo.broadcaster.model.stream.IStream;
import io.kneo.broadcaster.service.OneTimeStreamService;
import io.kneo.broadcaster.service.stream.RadioStationPool;
import io.kneo.core.controller.AbstractSecuredController;
import io.kneo.core.dto.cnst.PayloadType;
import io.kneo.core.dto.view.View;
import io.kneo.core.dto.view.ViewPage;
import io.kneo.core.service.UserService;
import io.kneo.core.util.RuntimeUtil;
import io.smallrye.mutiny.Uni;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class StreamController extends AbstractSecuredController<IStream, OneTimeStreamDTO> {
    private static final Logger LOGGER = LoggerFactory.getLogger(StreamController.class);

    private RadioStationPool radioStationPool;
    private OneTimeStreamService oneTimeStreamService;
    private Validator validator;

    public StreamController() {
        super(null);
    }

    @Inject
    public StreamController(UserService userService, RadioStationPool radioStationPool, OneTimeStreamService oneTimeStreamService, Validator validator) {
        super(userService);
        this.radioStationPool = radioStationPool;
        this.oneTimeStreamService = oneTimeStreamService;
        this.validator = validator;
    }

    public void setupRoutes(Router router) {
        String path = "/api/streams";
        router.route(path + "/*").handler(BodyHandler.create());
        router.get(path).handler(this::getAll);
        router.get(path + "/id/:id").handler(this::getById);
        router.get(path + "/:slugName").handler(this::getBySlugName);
        router.post(path + "/schedule/:brandId/:scriptId").handler(this::buildSchedule);
        router.post(path + "/run").handler(this::runOneTimeStream);
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

    private void getById(RoutingContext rc) {
        UUID id = UUID.fromString(rc.pathParam("id"));

        oneTimeStreamService.getById(id)
                .subscribe().with(
                        stream -> {
                            if (stream == null) {
                                rc.response().setStatusCode(404).end();
                            } else {
                                OneTimeStreamDTO dto = oneTimeStreamService.getDTO(stream);
                                rc.response().setStatusCode(200).end(JsonObject.mapFrom(dto).encode());
                            }
                        },
                        throwable -> {
                            LOGGER.error("Failed to get stream by id: {}", id, throwable);
                            rc.fail(throwable);
                        }
                );
    }

    private void getBySlugName(RoutingContext rc) {
        String slugName = rc.pathParam("slugName");

        oneTimeStreamService.getBySlugName(slugName)
                .subscribe().with(
                        stream -> {
                            if (stream == null) {
                                rc.response().setStatusCode(404).end();
                            } else {
                                OneTimeStreamDTO dto = oneTimeStreamService.getDTO(stream);
                                rc.response().setStatusCode(200).end(JsonObject.mapFrom(dto).encode());
                            }
                        },
                        throwable -> {
                            LOGGER.error("Failed to get stream by slugName: {}", slugName, throwable);
                            rc.fail(throwable);
                        }
                );
    }

    private void buildSchedule(RoutingContext rc) {
        UUID brandId = UUID.fromString(rc.pathParam("brandId"));
        UUID scriptId = UUID.fromString(rc.pathParam("scriptId"));

        getContextUser(rc, false, true)
                .chain(user -> oneTimeStreamService.buildStreamSchedule(brandId, scriptId, user))
                .subscribe().with(
                        dto -> rc.response()
                                .putHeader("Content-Type", "application/json")
                                .setStatusCode(200)
                                .end(JsonObject.mapFrom(dto).encode()),
                        throwable -> {
                            LOGGER.error("Failed to build schedule for brandId: {}, scriptId: {}", brandId, scriptId, throwable);
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
                            ignored -> rc.response().setStatusCode(204).end(),
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
