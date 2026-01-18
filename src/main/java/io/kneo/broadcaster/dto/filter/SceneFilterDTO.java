package io.kneo.broadcaster.dto.filter;

import io.kneo.broadcaster.model.cnst.SceneTimingMode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Setter
@Getter
@NoArgsConstructor
public class SceneFilterDTO implements IFilterDTO {
    private boolean activated = false;

    private SceneTimingMode timingMode;


    @Override
    public boolean isActivated() {
        return activated || hasAnyFilter();
    }

    @Override
    public boolean hasAnyFilter() {
        return timingMode != null;
    }
}