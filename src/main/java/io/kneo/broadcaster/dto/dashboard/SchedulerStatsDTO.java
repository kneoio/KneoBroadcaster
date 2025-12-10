package io.kneo.broadcaster.dto.dashboard;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class SchedulerStatsDTO {
    private boolean schedulerRunning;
    private String schedulerName;
    private LocalDateTime lastUpdated;
    private LocalDateTime lastEventTick;
    private int totalEventsChecked;
    private int totalEventsFired;
    private int totalEventErrors;
    private String lastFiredEventId;
    private LocalDateTime lastFiredEventTime;
}
