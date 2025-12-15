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
    @Setter
    private boolean played;

    public ScheduledSongEntry(SoundFragment soundFragment, LocalDateTime scheduledStartTime) {
        this.id = UUID.randomUUID();
        this.soundFragment = soundFragment;
        this.scheduledStartTime = scheduledStartTime;
        this.played = false;
    }

    public void markAsPlayed() {
        this.played = true;
    }

    public int getEstimatedDurationSeconds() {
        if (soundFragment.getLength() != null) {
            return (int) soundFragment.getLength().toSeconds();
        }
        return 180;
    }
}
