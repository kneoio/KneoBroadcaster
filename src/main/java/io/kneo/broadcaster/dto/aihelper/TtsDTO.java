package io.kneo.broadcaster.dto.aihelper;

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
}
