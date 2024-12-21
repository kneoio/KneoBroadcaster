package io.kneo.broadcaster.dto;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class SoundUploadDTO {
    private String introductionText;
    private boolean autoGenerateIntro;
    private boolean playImmediately;

}


