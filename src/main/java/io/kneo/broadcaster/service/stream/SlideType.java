package io.kneo.broadcaster.service.stream;

import lombok.Getter;

@Getter
public enum SlideType {
    SCHEDULED_TIMER("System Timer"),
    PLAYER_STARVATION("Low Buffer"),
    MANUAL_OVERRIDE("Manual Command"),
    CATCHUP("Late Adjustment"),
    ESTIMATED("Estimated");

    private final String triggerSource;

    SlideType(String triggerSource) {
        this.triggerSource = triggerSource;
    }

}