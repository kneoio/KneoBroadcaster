package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.dto.LabelDTO;
import io.kneo.broadcaster.dto.ProfileDTO;
import io.kneo.broadcaster.dto.VoiceFilterDTO;
import io.kneo.broadcaster.dto.actions.AiAgentActionsFactory;
import io.kneo.broadcaster.dto.actions.ProfileActionsFactory;
import io.kneo.broadcaster.dto.aiagent.AiAgentDTO;
import io.kneo.broadcaster.dto.aiagent.VoiceDTO;
import io.kneo.broadcaster.dto.of.CountryDTO;
import io.kneo.broadcaster.model.aiagent.TTSEngineType;
import io.kneo.broadcaster.model.cnst.LanguageTag;
import io.kneo.broadcaster.service.AiAgentService;
import io.kneo.broadcaster.service.ProfileService;
import io.kneo.broadcaster.service.RefService;
import io.kneo.core.controller.BaseController;
import io.kneo.core.dto.actions.ActionBox;
import io.kneo.core.dto.cnst.PayloadType;
import io.kneo.core.dto.view.View;
import io.kneo.core.dto.view.ViewPage;
import io.kneo.core.model.user.SuperUser;
import io.kneo.core.util.RuntimeUtil;
import io.kneo.officeframe.cnst.CountryCode;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
        router.route(HttpMethod.GET, "/api/dictionary/:type").handler(this::getDictionary);
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
            case "labels":
                service.getAllLabelsCount()
                        .onItem().transformToUni(count -> {
                            int maxPage = countMaxPage(count, size);
                            int offset = RuntimeUtil.calcStartEntry(page, size);
                            return service.getAllLabels(size, offset)
                                    .onItem().transform(dtoList -> {
                                        ViewPage viewPage = new ViewPage();
                                        viewPage.addPayload(PayloadType.CONTEXT_ACTIONS, new ActionBox());
                                        View<LabelDTO> dtoEntries = new View<>(dtoList, count, page, maxPage, size);
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
                VoiceFilterDTO filter = parseVoiceFilterDTO(rc);
                
                Uni.combine().all().unis(
                                filter != null ? service.getFilteredVoicesCount(filter) : service.getAllVoicesCount(TTSEngineType.ELEVENLABS),
                                filter != null ? service.getFilteredVoices(filter) : service.getAllVoices(TTSEngineType.ELEVENLABS)
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

    private VoiceFilterDTO parseVoiceFilterDTO(RoutingContext rc) {
        String filterParam = rc.request().getParam("filter");
        if (filterParam == null || filterParam.trim().isEmpty()) {
            return null;
        }
        VoiceFilterDTO dto = new VoiceFilterDTO();
        boolean any = false;
        try {
            String decodedFilter = URLDecoder.decode(filterParam, StandardCharsets.UTF_8);
            JsonObject json = new JsonObject(decodedFilter);
            
            if (json.containsKey("engineType")) {
                String engineType = json.getString("engineType");
                if (engineType != null && !engineType.trim().isEmpty()) {
                    try {
                        dto.setEngineType(TTSEngineType.valueOf(engineType.toUpperCase()));
                        any = true;
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
            
            String gender = json.getString("gender");
            if (gender != null && !gender.trim().isEmpty()) {
                dto.setGender(gender);
                any = true;
            }
            
            JsonArray l = json.getJsonArray("languages");
            if (l != null && !l.isEmpty()) {
                List<LanguageTag> languages = new ArrayList<>();
                for (Object o : l) {
                    if (o instanceof String str) {
                        try {
                            languages.add(LanguageTag.fromTag(str));
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                }
                if (!languages.isEmpty()) {
                    dto.setLanguages(languages);
                    any = true;
                }
            }
            
            JsonArray labels = json.getJsonArray("labels");
            if (labels != null && !labels.isEmpty()) {
                List<String> labelList = new ArrayList<>();
                for (Object o : labels) {
                    if (o instanceof String str) {
                        labelList.add(str);
                    }
                }
                if (!labelList.isEmpty()) {
                    dto.setLabels(labelList);
                    any = true;
                }
            }
            
            String searchTerm = json.getString("searchTerm");
            if (searchTerm != null && !searchTerm.trim().isEmpty()) {
                dto.setSearchTerm(searchTerm.trim());
                any = true;
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid filter JSON format: " + e.getMessage(), e);
        }
        return any ? dto : null;
    }

}
