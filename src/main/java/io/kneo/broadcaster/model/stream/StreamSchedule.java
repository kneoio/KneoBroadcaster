package io.kneo.broadcaster.model.stream;

import lombok.Getter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Getter
public class StreamSchedule {
    private final LocalDateTime createdAt;
    private final List<SceneScheduleEntry> sceneScheduleEntries;

    public StreamSchedule(LocalDateTime createdAt) {
        this.createdAt = createdAt;
        this.sceneScheduleEntries = new ArrayList<>();
    }

    public void addSceneSchedule(SceneScheduleEntry entry) {
        this.sceneScheduleEntries.add(entry);
    }

    public int getTotalScenes() {
        return sceneScheduleEntries.size();
    }

    public int getTotalSongs() {
        return sceneScheduleEntries.stream()
                .mapToInt(s -> s.getSongs().size())
                .sum();
    }

    public LocalDateTime getEstimatedEndTime() {
        if (sceneScheduleEntries.isEmpty()) {
            return createdAt;
        }
        return sceneScheduleEntries.getLast().getScheduledEndTime();
    }
}
