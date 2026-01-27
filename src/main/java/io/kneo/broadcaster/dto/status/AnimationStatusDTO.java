package io.kneo.broadcaster.dto.status;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AnimationStatusDTO {
    private boolean enabled;
    private String type;
    private double speed;
}
