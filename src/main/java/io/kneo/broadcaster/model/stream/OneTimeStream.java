package io.kneo.broadcaster.model.stream;

import io.kneo.broadcaster.dto.cnst.AiAgentStatus;
import io.kneo.broadcaster.dto.stream.StreamScheduleDTO;
import io.kneo.broadcaster.model.Script;
import io.kneo.broadcaster.model.brand.AiOverriding;
import io.kneo.broadcaster.model.brand.Brand;
import io.kneo.broadcaster.model.brand.BrandScriptEntry;
import io.kneo.broadcaster.model.brand.ProfileOverriding;
import io.kneo.broadcaster.model.cnst.ManagedBy;
import io.kneo.broadcaster.model.cnst.SubmissionPolicy;
import io.kneo.broadcaster.service.stream.IStreamManager;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.util.WebHelper;
import io.kneo.officeframe.cnst.CountryCode;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Setter
@Getter
public class OneTimeStream extends AbstractStream {
    private UUID id;
    private String slugName;
    private EnumMap<LanguageCode, String> localizedName = new EnumMap<>(LanguageCode.class);
    private ZoneId timeZone;
    private long bitRate;
    private ManagedBy managedBy = ManagedBy.MIX;
    private String color;
    private double popularityRate = 5;
    private UUID aiAgentId;
    private UUID profileId;
    private CountryCode country;

    private IStreamManager streamManager;
    private UUID baseBrandId;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
    private IStream sourceBrand;
    private Script script;
    private Map<String, Object> userVariables;
    private AiOverriding aiOverriding;
    private List<BrandScriptEntry> scripts;
    private AiAgentStatus aiAgentStatus;
    private StreamScheduleDTO schedule;

    public OneTimeStream(Brand sourceBrand, Script script, Map<String, Object> userVariables) {
        this.script = script;
        this.userVariables = userVariables;
        String displayName = buildOneTimeDisplayName();
        EnumMap<LanguageCode, String> localizedName = new EnumMap<>(LanguageCode.class);
        localizedName.put(LanguageCode.en, displayName);
        this.localizedName = localizedName;
        this.timeZone = sourceBrand.getTimeZone();
        this.color = WebHelper.generateRandomBrightColor();
        this.aiAgentId = sourceBrand.getAiAgentId();
        this.profileId = script.getDefaultProfileId();
        this.bitRate = sourceBrand.getBitRate();
        this.aiOverriding = sourceBrand.getAiOverriding();
        this.country =sourceBrand.getCountry();
        this.slugName = WebHelper.generateSlug(displayName + "-" + Integer.toHexString((int) (Math.random() * 0xFFFFFF)));
        this.scripts = List.of(new BrandScriptEntry(script.getId(), userVariables));
    }

    @Override
    public String getSourceBrandName() {
        return sourceBrand.getSlugName();
    }

    @Override
    public AiAgentStatus getAiAgentStatus() {
        return null;
    }

    @Override
    public String getDescription() {
        return "";
    }

    @Override
    public SubmissionPolicy getSubmissionPolicy() {
        return SubmissionPolicy.NOT_ALLOWED;
    }

    @Override
    public SubmissionPolicy getMessagingPolicy() {
        return SubmissionPolicy.NOT_ALLOWED;
    }


    @Override
    public void setAiAgentId(UUID aiAgentId) {

    }

    @Override
    public ProfileOverriding getProfileOverriding() {
        return null;
    }

    @Override
    public void setLastAgentContactAt(long l) {

    }


    @Override
    public String toString() {
        return String.format("OneTimeStream[id: %s, slug: %s, baseBrand: %s]", id, slugName, baseBrandId);
    }


    private String buildOneTimeDisplayName() {
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
