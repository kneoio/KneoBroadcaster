package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.dto.of.CountryDTO;
import io.kneo.broadcaster.dto.GenreDTO;
import io.kneo.broadcaster.service.GenreService;
import io.kneo.core.controller.BaseController;
import io.kneo.core.dto.actions.ActionBox;
import io.kneo.core.dto.cnst.PayloadType;
import io.kneo.core.dto.form.FormPage;
import io.kneo.core.dto.view.View;
import io.kneo.core.dto.view.ViewPage;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.AnonymousUser;
import io.kneo.core.util.RuntimeUtil;
import io.kneo.officeframe.cnst.CountryCode;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static io.kneo.core.util.RuntimeUtil.countMaxPage;

@ApplicationScoped
public class ReferencesController extends BaseController {

    @Inject
    GenreService service;

    public void setupRoutes(Router router) {
        router.route(HttpMethod.GET, "/api/genres").handler(this::getAll);
        router.route(HttpMethod.GET, "/api/countries").handler(this::getAllCountries);
        router.route(HttpMethod.GET, "/api/genres/:id").handler(this::get);
        router.route(HttpMethod.POST, "/api/genres").handler(this::upsert);
        router.route(HttpMethod.DELETE, "/api/genres/:id").handler(this::delete);
    }

    private void getAll(RoutingContext rc) {
        int page = Integer.parseInt(rc.request().getParam("page", "0"));
        int size = Integer.parseInt(rc.request().getParam("size", "10"));
        service.getAllCount(AnonymousUser.build())
                .onItem().transformToUni(count -> {
                    int maxPage = countMaxPage(count, size);
                    int pageNum = (page == 0) ? 1 : page;
                    int offset = RuntimeUtil.calcStartEntry(pageNum, size);
                    LanguageCode languageCode = resolveLanguage(rc);
                    return service.getAll(size, offset, languageCode)
                            .onItem().transform(dtoList -> {
                                ViewPage viewPage = new ViewPage();
                                viewPage.addPayload(PayloadType.CONTEXT_ACTIONS, new ActionBox());
                                View<GenreDTO> dtoEntries = new View<>(dtoList, count, pageNum, maxPage, size);
                                viewPage.addPayload(PayloadType.VIEW_DATA, dtoEntries);
                                return viewPage;
                            });
                })
                .subscribe().with(
                        viewPage -> rc.response().setStatusCode(200).end(JsonObject.mapFrom(viewPage).encode()),
                        rc::fail
                );
    }

    private void get(RoutingContext rc) {
        FormPage page = new FormPage();
        page.addPayload(PayloadType.CONTEXT_ACTIONS, new ActionBox());

        service.getDTO(UUID.fromString(rc.pathParam("id")), AnonymousUser.build(), resolveLanguage(rc))
                .onItem().transform(dto -> {
                    page.addPayload(PayloadType.DOC_DATA, dto);
                    return page;
                })
                .subscribe().with(
                        formPage -> rc.response().setStatusCode(200).end(JsonObject.mapFrom(formPage).encode()),
                        rc::fail
                );
    }

    private void getAllCountries(RoutingContext rc) {
        int page = Integer.parseInt(rc.request().getParam("page", "0"));
        int size = Integer.parseInt(rc.request().getParam("size", "10"));

        List<CountryDTO> allCountries = Arrays.stream(CountryCode.values())
                .filter(countryCode -> countryCode != CountryCode.UNKNOWN)
                .map(countryCode -> new CountryDTO(countryCode.getCode(), countryCode.getIsoCode(), countryCode.name()))
                .toList();

        int totalCount = allCountries.size();
        int maxPage = countMaxPage(totalCount, size);
        int pageNum = (page == 0) ? 1 : page;
        int offset = RuntimeUtil.calcStartEntry(pageNum, size);

        List<CountryDTO> pagedCountries = allCountries.stream()
                .skip(offset)
                .limit(size)
                .collect(Collectors.toList());

        ViewPage viewPage = new ViewPage();
        viewPage.addPayload(PayloadType.CONTEXT_ACTIONS, new ActionBox());
        View<CountryDTO> dtoEntries = new View<>(pagedCountries, totalCount, pageNum, maxPage, size);
        viewPage.addPayload(PayloadType.VIEW_DATA, dtoEntries);

        rc.response().setStatusCode(200).end(JsonObject.mapFrom(viewPage).encode());
    }

    private void upsert(RoutingContext rc) {
    }

    private void delete(RoutingContext rc) {
        rc.response().setStatusCode(200).end();
    }

}