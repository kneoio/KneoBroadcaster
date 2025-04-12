package io.kneo.broadcaster.model.stats;

import lombok.Builder;
import lombok.Getter;

import java.time.ZonedDateTime;

@Getter
@Builder
public class SliderStats {
    private ZonedDateTime scheduledTime;

}