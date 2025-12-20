package io.kneo.broadcaster.model.stream;

import io.kneo.broadcaster.model.soundfragment.SoundFragment;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

@Getter
public class ScheduledSongEntry {
    private final UUID id;
    private final SoundFragment soundFragment;
    private final LocalDateTime scheduledStartTime;
    @Setter
    private boolean played = false;

    public ScheduledSongEntry(SoundFragment soundFragment, LocalDateTime scheduledStartTime) {
        this.id = UUID.randomUUID();
        this.soundFragment = soundFragment;
        this.scheduledStartTime = scheduledStartTime;
    }

    public ScheduledSongEntry(UUID id, SoundFragment soundFragment, LocalDateTime scheduledStartTime) {
        this.id = id;
        this.soundFragment = soundFragment;
        this.scheduledStartTime = scheduledStartTime;
    }

    public int getEstimatedDurationSeconds() {
        if (soundFragment.getLength() != null) {
            return (int) soundFragment.getLength().toSeconds();
        }
        return 180;
    }

    public boolean fitsTimeScope(LocalTime currentTime) {
        LocalTime songTime = scheduledStartTime.toLocalTime();
        return !songTime.isBefore(currentTime) && 
               songTime.isBefore(currentTime.plusSeconds(getEstimatedDurationSeconds()));
    }
}
