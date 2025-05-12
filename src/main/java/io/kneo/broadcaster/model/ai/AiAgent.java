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

    private List<String> preferredVoice;

    public AiAgent() {
        this.preferredVoice = new ArrayList<>();
    }


    public void addPreferredVoiceName(String voiceName) {
        this.preferredVoice.add(voiceName);
    }

    public void removePreferredVoiceName(String voiceName) {
        this.preferredVoice.remove(voiceName);
    }
}
