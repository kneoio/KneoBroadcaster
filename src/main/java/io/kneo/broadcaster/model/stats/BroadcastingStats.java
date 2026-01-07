package io.kneo.broadcaster.model.stats;

import io.kneo.broadcaster.model.cnst.StreamStatus;
import io.kneo.broadcaster.model.soundfragment.SoundFragment;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.ZonedDateTime;

@Setter
@Getter
@NoArgsConstructor
public class BroadcastingStats {
    private StreamStatus status = StreamStatus.OFF_LINE;
    private int fragmentsInQueue;
    private ZonedDateTime started;
    private SoundFragment current;
    private boolean aiControlAllowed;


}
