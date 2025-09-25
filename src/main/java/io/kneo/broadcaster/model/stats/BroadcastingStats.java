package io.kneo.broadcaster.model.stats;

import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.model.SoundFragment;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.ZonedDateTime;

@Setter
@Getter
@NoArgsConstructor
public class BroadcastingStats {
    private RadioStationStatus status = RadioStationStatus.OFF_LINE;
    private int fragmentsInQueue;
    private ZonedDateTime started;
    private int listeners;
    private SoundFragment current;
    private boolean aiControlAllowed;


}
