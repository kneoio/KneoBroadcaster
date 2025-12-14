package io.kneo.broadcaster.service;

import io.kneo.broadcaster.dto.radiostation.OneTimeStreamRunReqDTO;
import io.kneo.broadcaster.model.Script;
import io.kneo.broadcaster.model.brand.Brand;
import io.kneo.broadcaster.model.brand.BrandScriptEntry;
import io.kneo.broadcaster.model.stream.OneTimeStream;
import io.kneo.broadcaster.repository.BrandRepository;
import io.kneo.broadcaster.repository.OneTimeStreamRepository;
import io.kneo.broadcaster.repository.ScriptRepository;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.IUser;
import io.kneo.core.util.WebHelper;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class OneTimeStreamService {
    private static final Logger LOGGER = LoggerFactory.getLogger(OneTimeStreamService.class);

    @Inject
    BrandRepository brandRepository;

    @Inject
    ScriptRepository scriptRepository;

    @Inject
    OneTimeStreamRepository oneTimeStreamRepository;

    @Inject
    Provider<RadioService> radioService;

    public Uni<Void> runOneTimeStream(OneTimeStreamRunReqDTO dto, IUser user) {
        return brandRepository.findById(dto.getBrandId(), user, true)
                .chain(sourceBrand -> scriptRepository.findById(dto.getScriptId(), user, false)
                        .chain(script -> Uni.createFrom().item(
                                buildOneTimeStream(sourceBrand, script, dto)
                        ))
                        .chain(s -> {
                            LOGGER.info("OneTimeStream: Initializing stream slugName={}", s);
                            return radioService.get().initializeOneTimeStream(s).replaceWithVoid();
                        })
                );
    }

    private OneTimeStream buildOneTimeStream(Brand sourceBrand, Script script, OneTimeStreamRunReqDTO dto) {
        OneTimeStream doc = new OneTimeStream();

        String displayName = buildOneTimeDisplayName(script, dto.getUserVariables());
        EnumMap<LanguageCode, String> localizedName = new EnumMap<>(LanguageCode.class);
        localizedName.put(LanguageCode.en, displayName);
        doc.setLocalizedName(localizedName);
        doc.setManagedBy(sourceBrand.getManagedBy());
        doc.setTimeZone(sourceBrand.getTimeZone());
        doc.setColor(WebHelper.generateRandomBrightColor());
        doc.setPopularityRate(5);
        doc.setAiAgentId(sourceBrand.getAiAgentId());
        doc.setProfileId(script.getDefaultProfileId() != null ? script.getDefaultProfileId() : sourceBrand.getProfileId());
        doc.setAiOverriding(sourceBrand.getAiOverriding());
        doc.setBitRate(sourceBrand.getBitRate());
        doc.setCountry(sourceBrand.getCountry());
        doc.setSlugName(
                WebHelper.generateSlug(displayName + "-" + Integer.toHexString((int) (Math.random() * 0xFFFFFF)))
        );
        doc.setScripts(List.of(new BrandScriptEntry(dto.getScriptId(), dto.getUserVariables())));
        oneTimeStreamRepository.insert(doc);
        return doc;
    }

    private String buildOneTimeDisplayName(Script script, Map<String, Object> userVariables) {
        String base = script.getSlugName() != null && !script.getSlugName().trim().isEmpty()
                ? script.getSlugName()
                : script.getName();

        List<String> parts = new ArrayList<>();
        parts.add(base);

        if (userVariables != null && !userVariables.isEmpty()) {
            userVariables.entrySet().stream()
                    .sorted(Comparator.comparing(Map.Entry::getKey))
                    .forEach(e -> {
                        Object v = e.getValue();
                        if (v != null) {
                            String s = v.toString().trim();
                            if (!s.isEmpty()) {
                                parts.add(s);
                            }
                        }
                    });
        }
        return String.join(" ", parts);
    }

    public Uni<OneTimeStream> getBySlugName(OneTimeStream ots) {
        return oneTimeStreamRepository.getBySlugName(ots.getSlugName());
    }
}
