package io.kneo.broadcaster.dto.aihelper;

import io.kneo.broadcaster.model.aiagent.TTSEngineType;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class TtsDTO {
    private String primaryVoice;
    private String secondaryVoice;
    private String secondaryVoiceName;
    private TTSEngineType ttsEngineType;
}
