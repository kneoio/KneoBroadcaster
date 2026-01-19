package io.kneo.broadcaster.model.stream;

import lombok.Getter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Getter
public class StreamSchedule {
    private final LocalDateTime createdAt;
    private final List<LiveScene> liveScenes;

    public StreamSchedule(LocalDateTime createdAt) {
        this.createdAt = createdAt;
        this.liveScenes = new ArrayList<>();
    }

    public void addSceneSchedule(LiveScene entry) {
        this.liveScenes.add(entry);
    }

    public int getTotalScenes() {
        return liveScenes.size();
    }

    public int getTotalSongs() {
        return liveScenes.stream()
                .mapToInt(s -> s.getSongs().size())
                .sum();
    }

    public LocalDateTime getEstimatedEndTime() {
        if (liveScenes.isEmpty()) {
            return createdAt;
        }
        return liveScenes.getLast().getScheduledEndTime();
    }
}
