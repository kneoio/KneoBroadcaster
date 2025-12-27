package io.kneo.broadcaster.model.stream;

import io.kneo.broadcaster.model.soundfragment.SoundFragment;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
public class ScheduledSongEntry {
    private final UUID id;
    private final SoundFragment soundFragment;
    private final LocalDateTime scheduledStartTime;
    private final int durationSeconds;
    @Setter
    private boolean played = false;

    public ScheduledSongEntry(SoundFragment soundFragment, LocalDateTime scheduledStartTime) {
        this.id = UUID.randomUUID();
        this.soundFragment = soundFragment;
        this.scheduledStartTime = scheduledStartTime;
        this.durationSeconds = soundFragment.getLength() != null 
            ? (int) soundFragment.getLength().toSeconds() 
            : 180;
    }

    public ScheduledSongEntry(UUID id, SoundFragment soundFragment, LocalDateTime scheduledStartTime) {
        this.id = id;
        this.soundFragment = soundFragment;
        this.scheduledStartTime = scheduledStartTime;
        this.durationSeconds = soundFragment.getLength() != null 
            ? (int) soundFragment.getLength().toSeconds() 
            : 180;
    }

    public ScheduledSongEntry(UUID id, SoundFragment soundFragment, LocalDateTime scheduledStartTime, int durationSeconds) {
        this.id = id;
        this.soundFragment = soundFragment;
        this.scheduledStartTime = scheduledStartTime;
        this.durationSeconds = durationSeconds;
    }

    public int getDurationSeconds() {
        return durationSeconds;
    }
}
