package io.kneo.broadcaster.model.radiostation;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AiOverriding {
    private String name;
    private String prompt;
    private double talkativity;
    private String preferredVoice;
}
