package io.kneo.broadcaster.model.stream;

import io.kneo.broadcaster.model.Scene;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
public class SceneScheduleEntry {
    private final UUID sceneId;
    private final String sceneTitle;
    private final LocalDateTime scheduledStartTime;
    private final int durationSeconds;
    private final List<ScheduledSongEntry> songs;

    public SceneScheduleEntry(Scene scene, LocalDateTime scheduledStartTime) {
        this.sceneId = scene.getId();
        this.sceneTitle = scene.getTitle();
        this.scheduledStartTime = scheduledStartTime;
        this.durationSeconds = scene.getDurationSeconds();
        this.songs = new ArrayList<>();
    }

    public void addSong(ScheduledSongEntry song) {
        this.songs.add(song);
    }

    public void addSongs(List<ScheduledSongEntry> songList) {
        this.songs.addAll(songList);
    }

    public LocalDateTime getScheduledEndTime() {
        return scheduledStartTime.plusSeconds(durationSeconds);
    }
}
