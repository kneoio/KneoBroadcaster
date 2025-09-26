package io.kneo.broadcaster.dto.radiostation;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AiOverridingDTO {
    private String name;
    private String prompt;
    private double talkativity;
    private String preferredVoice;
}
