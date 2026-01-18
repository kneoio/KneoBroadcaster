package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.dto.BrandListenerDTO;
import io.kneo.broadcaster.dto.ListenerDTO;
import io.kneo.broadcaster.dto.ListenerFilterDTO;
import io.kneo.broadcaster.model.Listener;
import io.kneo.broadcaster.service.ListenerService;
import io.kneo.broadcaster.util.ProblemDetailsUtil;
import io.kneo.core.controller.AbstractSecuredController;
import io.kneo.core.dto.actions.ActionBox;
import io.kneo.core.dto.cnst.PayloadType;
import io.kneo.core.dto.form.FormPage;
import io.kneo.core.dto.view.View;
import io.kneo.core.dto.view.ViewPage;
import io.kneo.core.service.UserService;
import io.kneo.core.util.RuntimeUtil;
import io.kneo.officeframe.cnst.CountryCode;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class ListenerController extends AbstractSecuredController<Listener, ListenerDTO> {
    private static final Logger LOGGER = LoggerFactory.getLogger(ListenerController.class);

    private ListenerService service;
    private Validator validator;

    public ListenerController() {
        super(null);
    }

    @Inject
    public ListenerController(UserService userService, ListenerService service, Validator validator) {
        super(userService);
        this.service = service;
        this.validator = validator;
    }

    public void setupRoutes(Router router) {
        String path = "/api/listeners";
        router.route(path + "*").handler(BodyHandler.create());
        router.route(path + "*").handler(this::addHeaders);
        router.get(path).handler(this::get);
        router.get(path + "/available-listeners").handler(this::getBrandListeners);
        router.get(path + "/available-listeners/:id").handler(this::getBrandListenerById);

        router.get(path + "/:id").handler(this::getById);
        router.post(path + "/:id?").handler(this::upsert);
        router.delete(path + "/:id").handler(this::delete);
        router.get(path + "/:id/access").handler(this::getDocumentAccess);
    }

    private void get(RoutingContext rc) {
        int page = Integer.parseInt(rc.request().getParam("page", "1"));
        int size = Integer.parseInt(rc.request().getParam("size", "10"));
        ListenerFilterDTO filter = parseFilterDTO(rc);

        getContextUser(rc, false, true)
                .chain(user -> Uni.combine().all().unis(
                        service.getAllCount(user, filter),
                        service.getAllDTO(size, (page - 1) * size, user, filter)
                ).asTuple().map(tuple -> {
                    ViewPage viewPage = new ViewPage();
                    View<ListenerDTO> dtoEntries = new View<>(tuple.getItem2(),
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

        getContextUser(rc, false, true)
                .chain(user -> {
                    if ("new".equals(id)) {
                        return service.getDTOTemplate(user, resolveLanguage(rc))
                                .map(dto -> Tuple2.of(dto, user));
                    }
                    return service.getDTO(UUID.fromString(id), user, resolveLanguage(rc))
                            .map(doc -> Tuple2.of(doc, user));
                })
                .subscribe().with(
                        tuple -> {
                            ListenerDTO doc = tuple.getItem1();
                            FormPage page = new FormPage();
                            page.addPayload(PayloadType.DOC_DATA, doc);
                            page.addPayload(PayloadType.CONTEXT_ACTIONS, new ActionBox());
                            rc.response().setStatusCode(200).end(JsonObject.mapFrom(page).encode());
                        },
                        rc::fail
                );
    }

    private void getBrandListeners(RoutingContext rc) {
        String brandName = rc.request().getParam("brand");
        int page = Integer.parseInt(rc.request().getParam("page", "1"));
        int size = Integer.parseInt(rc.request().getParam("size", "10"));
        ListenerFilterDTO filter = parseFilterDTO(rc);

        getContextUser(rc, false, true)
                .chain(user -> Uni.combine().all().unis(
                        service.getBrandListeners(brandName, size, (page - 1) * size, user, filter),
                        service.getCountBrandListeners(brandName, user, filter)
                ).asTuple().map(tuple -> {
                    ViewPage viewPage = new ViewPage();
                    View<BrandListenerDTO> dtoEntries = new View<>(tuple.getItem1(),
                            tuple.getItem2(), page,
                            RuntimeUtil.countMaxPage(tuple.getItem2(), size),
                            size);
                    viewPage.addPayload(PayloadType.VIEW_DATA, dtoEntries);
                    return viewPage;
                }))
                .subscribe().with(
                        viewPage -> rc.response().setStatusCode(200).end(JsonObject.mapFrom(viewPage).encode()),
                        rc::fail
                );
    }

    private void getBrandListenerById(RoutingContext rc) {
        String id = rc.pathParam("id");
        String brandName = rc.request().getParam("brand");

        getContextUser(rc, false, true)
                .chain(user -> service.getBrandListeners(brandName, 100, 0, user, null)
                        .map(brandListeners -> brandListeners.stream()
                                .filter(bl -> bl.getListenerDTO().getId().toString().equals(id))
                                .findFirst()
                                .orElse(null)))
                .subscribe().with(
                        brandListener -> {
                            if (brandListener == null) {
                                rc.response().setStatusCode(404).end();
                                return;
                            }
                            FormPage page = new FormPage();
                            page.addPayload(PayloadType.DOC_DATA, brandListener);
                            page.addPayload(PayloadType.CONTEXT_ACTIONS, new ActionBox());
                            rc.response().setStatusCode(200).end(JsonObject.mapFrom(page).encode());
                        },
                        rc::fail
                );
    }

    private void upsert(RoutingContext rc) {
        try {
            if (!validateJsonBody(rc)) {
                return;
            }

            ListenerDTO dto = rc.body().asJsonObject().mapTo(ListenerDTO.class);
            String id = rc.pathParam("id");
            String contextBrandSlug = parseNullableParam(rc, "contextBrandSlug");

            if (!validateAndRespond(rc, dto)) {
                return;
            }

            getContextUser(rc, false, true)
                    .chain(user -> service.upsert(id, dto, contextBrandSlug, user))
                    .subscribe().with(
                            doc -> sendUpsertResponse(rc, doc, id),
                            throwable -> handleUpsertFailure(rc, throwable)
                    );

        } catch (Exception e) {
            rc.fail(400, e instanceof IllegalArgumentException ? e : new IllegalArgumentException("Invalid JSON payload"));
        }
    }

    private String parseNullableParam(RoutingContext rc, String paramName) {
        String value = rc.request().getParam(paramName);
        return (value == null || value.isEmpty()) ? null : value;
    }

    private boolean validateAndRespond(RoutingContext rc, ListenerDTO dto) {
        Set<ConstraintViolation<ListenerDTO>> violations = validator.validate(dto);
        if (violations == null || violations.isEmpty()) {
            return true;
        }

        Map<String, List<String>> fieldErrors = new HashMap<>();
        for (ConstraintViolation<ListenerDTO> v : violations) {
            String field = v.getPropertyPath().toString();
            fieldErrors.computeIfAbsent(field, k -> new ArrayList<>()).add(v.getMessage());
        }
        String detail = fieldErrors.entrySet().stream()
                .flatMap(e -> e.getValue().stream().map(msg -> e.getKey() + ": " + msg))
                .collect(Collectors.joining(", "));
        ProblemDetailsUtil.respondValidationError(rc, detail, fieldErrors);
        return false;
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

    private ListenerFilterDTO parseFilterDTO(RoutingContext rc) {
        ListenerFilterDTO filterDTO = new ListenerFilterDTO();
        boolean hasAnyFilter = false;

        // Parse countries filter (comma-separated enum values)
        String countriesParam = rc.request().getParam("country");
        if (countriesParam != null && !countriesParam.trim().isEmpty()) {
            List<CountryCode> countries = new ArrayList<>();
            String[] countryArray = countriesParam.split(",");
            for (String country : countryArray) {
                String trimmedCountry = country.trim();
                if (!trimmedCountry.isEmpty()) {
                    try {
                        countries.add(CountryCode.valueOf(trimmedCountry));
                    } catch (IllegalArgumentException e) {
                        LOGGER.warn("Invalid country code: {}", trimmedCountry);
                    }
                }
            }
            if (!countries.isEmpty()) {
                filterDTO.setCountries(countries);
                hasAnyFilter = true;
            }
        }

        // Parse activated flag
        String activatedParam = rc.request().getParam("filterActivated");
        if (activatedParam != null && !activatedParam.trim().isEmpty()) {
            filterDTO.setActivated(Boolean.parseBoolean(activatedParam));
            hasAnyFilter = true;
        }

        return hasAnyFilter ? filterDTO : null;
    }
}