package io.kneo.broadcaster.controller.stream;

import lombok.Getter;

@Getter
public enum SlideType {
    SCHEDULED_TIMER("System Timer"),
    PLAYER_STARVATION("Low Buffer"),
    MANUAL_OVERRIDE("Manual Command"),
    CATCHUP("Late Adjustment");

    private final String triggerSource;

    SlideType(String triggerSource) {
        this.triggerSource = triggerSource;
    }

}