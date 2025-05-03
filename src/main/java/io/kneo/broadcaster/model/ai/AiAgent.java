package io.kneo.broadcaster.model.ai;

import io.kneo.core.localization.LanguageCode;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Setter
@Getter
public class AiAgent {
    private String name;
    private LanguageCode language;

    private List<String> preferredVoiceNames;

    public AiAgent() {
        this.preferredVoiceNames = new ArrayList<>();
    }


    public void addPreferredVoiceName(String voiceName) {
        this.preferredVoiceNames.add(voiceName);
    }

    public void removePreferredVoiceName(String voiceName) {
        this.preferredVoiceNames.remove(voiceName);
    }
}
