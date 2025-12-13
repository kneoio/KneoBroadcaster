package io.kneo.broadcaster.service;

import io.kneo.broadcaster.dto.radiostation.OneTimeStreamRunReqDTO;
import io.kneo.broadcaster.model.Script;
import io.kneo.broadcaster.model.brand.Brand;
import io.kneo.broadcaster.model.brand.BrandScriptEntry;
import io.kneo.broadcaster.model.cnst.SubmissionPolicy;
import io.kneo.broadcaster.repository.BrandRepository;
import io.kneo.broadcaster.repository.ScriptRepository;
import io.kneo.broadcaster.repository.soundfragment.SoundFragmentRepository;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.IUser;
import io.kneo.core.util.WebHelper;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class OneTimeStreamService {

    @Inject
    BrandRepository brandRepository;

    @Inject
    ScriptRepository scriptRepository;

    @Inject
    SoundFragmentRepository soundFragmentRepository;

    @Inject
    Provider<RadioService> radioService;

    public Uni<Void> runOneTimeStream(OneTimeStreamRunReqDTO dto, IUser user) {
        return brandRepository.findById(dto.getBrandId(), user, true)
                .chain(sourceBrand -> scriptRepository.findById(dto.getScriptId(), user, false)
                        .chain(script -> {
                            Brand oneTimeBrand = buildOneTimeBrand(sourceBrand, script, dto);
                            return brandRepository.insert(oneTimeBrand, user);
                        })
                        .chain(savedBrand -> {
                            return soundFragmentRepository.copyBrandSoundFragments(sourceBrand.getId(), savedBrand.getId())
                                    .replaceWith(savedBrand);
                        })
                        .chain(savedBrand -> radioService.get().initializeStation(savedBrand.getSlugName()).replaceWithVoid())
                );
    }

    private Brand buildOneTimeBrand(Brand sourceBrand, Script script, OneTimeStreamRunReqDTO dto) {
        Brand doc = new Brand();

        String displayName = buildOneTimeDisplayName(script, dto.getUserVariables());
        EnumMap<LanguageCode, String> localizedName = new EnumMap<>(LanguageCode.class);
        localizedName.put(LanguageCode.en, displayName);
        doc.setLocalizedName(localizedName);
        doc.setManagedBy(sourceBrand.getManagedBy());
        doc.setTimeZone(sourceBrand.getTimeZone());
        doc.setColor(WebHelper.generateRandomBrightColor());
        doc.setAiAgentId(sourceBrand.getAiAgentId());
        doc.setProfileId(script.getDefaultProfileId() != null ? script.getDefaultProfileId() : sourceBrand.getProfileId());
        doc.setAiOverriding(sourceBrand.getAiOverriding());
        doc.setBitRate(sourceBrand.getBitRate());

        doc.setOneTimeStreamPolicy(SubmissionPolicy.NOT_ALLOWED);
        doc.setSubmissionPolicy(SubmissionPolicy.NOT_ALLOWED);
        doc.setMessagingPolicy(SubmissionPolicy.NOT_ALLOWED);
        doc.setIsTemporary(1);
        doc.setSlugName(
                WebHelper.generateSlug(displayName + "-" + Integer.toHexString((int) (Math.random() * 0xFFFFFF)))
        );
        doc.setScripts(List.of(new BrandScriptEntry(dto.getScriptId(), dto.getUserVariables())));

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
}
