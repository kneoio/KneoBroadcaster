package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.dto.GenreDTO;
import io.kneo.broadcaster.dto.ProfileDTO;
import io.kneo.broadcaster.dto.actions.AiAgentActionsFactory;
import io.kneo.broadcaster.dto.actions.ProfileActionsFactory;
import io.kneo.broadcaster.dto.ai.AiAgentDTO;
import io.kneo.broadcaster.dto.ai.VoiceDTO;
import io.kneo.broadcaster.dto.of.CountryDTO;
import io.kneo.broadcaster.service.AiAgentService;
import io.kneo.broadcaster.service.RefService;
import io.kneo.broadcaster.service.ProfileService;
import io.kneo.core.controller.BaseController;
import io.kneo.core.dto.actions.ActionBox;
import io.kneo.core.dto.cnst.PayloadType;
import io.kneo.core.dto.form.FormPage;
import io.kneo.core.dto.view.View;
import io.kneo.core.dto.view.ViewPage;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.AnonymousUser;
import io.kneo.core.model.user.SuperUser;
import io.kneo.core.util.RuntimeUtil;
import io.kneo.officeframe.cnst.CountryCode;
import io.smallrye.mutiny.Uni;
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
public class RefController extends BaseController {

    @Inject
    RefService service;

    @Inject
    AiAgentService aiAgentService;

    @Inject
    ProfileService profileService;

    public void setupRoutes(Router router) {
        router.route(HttpMethod.GET, "/api/genres").handler(this::getAllGenres);
        router.route(HttpMethod.GET, "/api/countries").handler(this::getAllCountries);
        router.route(HttpMethod.GET, "/api/dictionary/:type").handler(this::getDictionary);
        router.route(HttpMethod.GET, "/api/genres/:id").handler(this::get);
        router.route(HttpMethod.POST, "/api/genres").handler(this::upsert);
        router.route(HttpMethod.DELETE, "/api/genres/:id").handler(this::delete);
        router.route(HttpMethod.GET, "/alexa/skill").handler(this::getSkill);
    }

    @Deprecated
    private void getAllGenres(RoutingContext rc) {
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

    @Deprecated
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

    private void getDictionary(RoutingContext rc) {
        String type = rc.pathParam("type");
        int page = Integer.parseInt(rc.request().getParam("page", "1"));
        int size = Integer.parseInt(rc.request().getParam("size", "10"));

        switch (type) {
            case "agents":
                SuperUser su = SuperUser.build();
                Uni.combine().all().unis(
                                aiAgentService.getAllCount(su),
                                aiAgentService.getAll(size, (page - 1) * size, su)
                        )
                        .asTuple()
                        .map(tuple -> {
                            ViewPage viewPage = new ViewPage();
                            View<AiAgentDTO> dtoEntries = new View<>(
                                    tuple.getItem2(),
                                    tuple.getItem1(),
                                    page,
                                    RuntimeUtil.countMaxPage(tuple.getItem1(), size),
                                    size);
                            viewPage.addPayload(PayloadType.VIEW_DATA, dtoEntries);

                            ActionBox actions = AiAgentActionsFactory.getViewActions(su.getActivatedRoles());
                            viewPage.addPayload(PayloadType.CONTEXT_ACTIONS, actions);
                            return viewPage;
                        })
                        .subscribe().with(
                                viewPage -> rc.response().setStatusCode(200).end(JsonObject.mapFrom(viewPage).encode()),
                                rc::fail
                        );
                break;

            case "profiles":
                SuperUser suProfiles = SuperUser.build();
                Uni.combine().all().unis(
                                profileService.getAllCount(suProfiles),
                                profileService.getAll(size, (page - 1) * size, suProfiles)
                        )
                        .asTuple()
                        .map(tuple -> {
                            ViewPage viewPage = new ViewPage();
                            View<ProfileDTO> dtoEntries = new View<>(
                                    tuple.getItem2(),
                                    tuple.getItem1(),
                                    page,
                                    RuntimeUtil.countMaxPage(tuple.getItem1(), size),
                                    size);
                            viewPage.addPayload(PayloadType.VIEW_DATA, dtoEntries);

                            ActionBox actions = ProfileActionsFactory.getViewActions(suProfiles.getActivatedRoles());
                            viewPage.addPayload(PayloadType.CONTEXT_ACTIONS, actions);
                            return viewPage;
                        })
                        .subscribe().with(
                                viewPage -> rc.response().setStatusCode(200).end(JsonObject.mapFrom(viewPage).encode()),
                                rc::fail
                        );
                break;

            case "genres":
                service.getAllCount(AnonymousUser.build())
                        .onItem().transformToUni(count -> {
                            int maxPage = countMaxPage(count, size);
                            int offset = RuntimeUtil.calcStartEntry(page, size);
                            LanguageCode languageCode = resolveLanguage(rc);
                            return service.getAll(size, offset, languageCode)
                                    .onItem().transform(dtoList -> {
                                        ViewPage viewPage = new ViewPage();
                                        viewPage.addPayload(PayloadType.CONTEXT_ACTIONS, new ActionBox());
                                        View<GenreDTO> dtoEntries = new View<>(dtoList, count, page, maxPage, size);
                                        viewPage.addPayload(PayloadType.VIEW_DATA, dtoEntries);
                                        return viewPage;
                                    });
                        })
                        .subscribe().with(
                                viewPage -> rc.response().setStatusCode(200).end(JsonObject.mapFrom(viewPage).encode()),
                                rc::fail
                        );
                break;

            case "countries":
                List<CountryDTO> allCountries = Arrays.stream(CountryCode.values())
                        .filter(countryCode -> countryCode != CountryCode.UNKNOWN)
                        .map(countryCode -> new CountryDTO(countryCode.getCode(), countryCode.getIsoCode(), countryCode.name()))
                        .toList();

                int totalCount = allCountries.size();
                int maxPage = countMaxPage(totalCount, size);
                int offset = RuntimeUtil.calcStartEntry(page, size);

                List<CountryDTO> pagedCountries = allCountries.stream()
                        .skip(offset)
                        .limit(size)
                        .collect(Collectors.toList());

                ViewPage viewPage = new ViewPage();
                viewPage.addPayload(PayloadType.CONTEXT_ACTIONS, new ActionBox());
                View<CountryDTO> dtoEntries = new View<>(pagedCountries, totalCount, page, maxPage, size);
                viewPage.addPayload(PayloadType.VIEW_DATA, dtoEntries);

                rc.response().setStatusCode(200).end(JsonObject.mapFrom(viewPage).encode());
                break;

            case "voices":
                Uni.combine().all().unis(
                                service.getAllVoicesCount(),
                                service.getAllVoices()
                        )
                        .asTuple()
                        .map(tuple -> {
                            List<VoiceDTO> allVoices = tuple.getItem2();
                            int voicesCount = tuple.getItem1();
                            int maxVoicePage = countMaxPage(voicesCount, size);
                            int voiceOffset = RuntimeUtil.calcStartEntry(page, size);

                            List<VoiceDTO> pagedVoices = allVoices.stream()
                                    .skip(voiceOffset)
                                    .limit(size)
                                    .collect(Collectors.toList());

                            ViewPage voiceViewPage = new ViewPage();
                            voiceViewPage.addPayload(PayloadType.CONTEXT_ACTIONS, new ActionBox());
                            View<VoiceDTO> voiceDtoEntries = new View<>(pagedVoices, voicesCount, page, maxVoicePage, size);
                            voiceViewPage.addPayload(PayloadType.VIEW_DATA, voiceDtoEntries);
                            return voiceViewPage;
                        })
                        .subscribe().with(
                                voiceViewPage -> rc.response().setStatusCode(200).end(JsonObject.mapFrom(voiceViewPage).encode()),
                                rc::fail
                        );
                break;

            default:
                rc.response().setStatusCode(404).end();
        }
    }

    private void getSkill(RoutingContext rc) {
        JsonObject response = new JsonObject()
                .put("version", "1.0")
                .put("response", new JsonObject()
                        .put("shouldEndSession", true)
                        .put("directives", List.of(
                                new JsonObject()
                                        .put("type", "AudioPlayer.Play")
                                        .put("playBehavior", "REPLACE_ALL")
                                        .put("audioItem", new JsonObject()
                                                .put("stream", new JsonObject()
                                                        .put("token", "aye-ayes-ear-001")
                                                        .put("url", "https://mixpla.online/aye-ayes-ear/radio/stream.m3u8")
                                                        .put("offsetInMilliseconds", 0)
                                                )
                                        )
                        ))
                );

        rc.response()
                .putHeader("Content-Type", "application/json")
                .setStatusCode(200)
                .end(response.encode());
    }

    private void upsert(RoutingContext rc) {
    }

    private void delete(RoutingContext rc) {
        rc.response().setStatusCode(200).end();
    }

}