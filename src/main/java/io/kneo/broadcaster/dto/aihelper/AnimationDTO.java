package io.kneo.broadcaster.dto.aihelper;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AnimationDTO {
    private boolean enabled;
    private String type;
    private double speed;

    public AnimationDTO(boolean enabled, String type, double speed) {
        this.enabled = enabled;
        this.type = type;
        this.speed = speed;
    }
}
