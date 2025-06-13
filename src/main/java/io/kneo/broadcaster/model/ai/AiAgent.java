package io.kneo.broadcaster.model.ai;

import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.SimpleReferenceEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@Setter
@Getter
public class AiAgent  extends SimpleReferenceEntity {
    private String name;
    private LanguageCode preferredLang;
    private ZoneId timeZone;
    private String mainPrompt;
    private List<Voice> preferredVoice;
    private List<Tool> enabledTools;

    public AiAgent() {
        this.preferredVoice = new ArrayList<>();
    }

}
