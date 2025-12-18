package io.kneo.broadcaster.model.stream;

import lombok.Getter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Getter
public class StreamSchedule {
    private final LocalDateTime createdAt;
    private final List<SceneScheduleEntry> sceneSchedules;

    public StreamSchedule(LocalDateTime createdAt) {
        this.createdAt = createdAt;
        this.sceneSchedules = new ArrayList<>();
    }

    public void addSceneSchedule(SceneScheduleEntry entry) {
        this.sceneSchedules.add(entry);
    }

    public int getTotalScenes() {
        return sceneSchedules.size();
    }

    public int getTotalSongs() {
        return sceneSchedules.stream()
                .mapToInt(s -> s.getSongs().size())
                .sum();
    }

    public LocalDateTime getEstimatedEndTime() {
        if (sceneSchedules.isEmpty()) {
            return createdAt;
        }
        return sceneSchedules.getLast().getScheduledEndTime();
    }
}
