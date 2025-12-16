package io.kneo.broadcaster.model.stream;

import io.kneo.broadcaster.model.PlaylistRequest;
import io.kneo.broadcaster.model.Scene;
import io.kneo.broadcaster.model.Script;
import io.kneo.broadcaster.model.brand.Brand;
import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.model.cnst.WayOfSourcing;
import io.kneo.broadcaster.model.soundfragment.SoundFragment;
import io.kneo.broadcaster.service.stream.ScheduleSongSupplier;
import io.smallrye.mutiny.Uni;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Getter
public class StreamSchedule {
    private final LocalDateTime createdAt;
    private final List<SceneScheduleEntry> sceneSchedules;

    public StreamSchedule() {
        this.createdAt = LocalDateTime.now();
        this.sceneSchedules = new ArrayList<>();
    }

    public StreamSchedule(LocalDateTime createdAt) {
        this.createdAt = createdAt;
        this.sceneSchedules = new ArrayList<>();
    }

    public Uni<StreamSchedule> build(Script script, Brand sourceBrand, ScheduleSongSupplier songSupplier) {
        List<Scene> scenes = script.getScenes();

        if (scenes == null || scenes.isEmpty()) {
            return Uni.createFrom().item(this);
        }

        List<Uni<SceneScheduleEntry>> sceneUnis = new ArrayList<>();
        LocalDateTime sceneStartTime = createdAt;

        for (Scene scene : scenes) {
            LocalDateTime finalSceneStartTime = sceneStartTime;
            Uni<SceneScheduleEntry> sceneUni = fetchSongsForScene(sourceBrand, scene, songSupplier)
                    .map(songs -> {
                        SceneScheduleEntry entry = new SceneScheduleEntry(scene, finalSceneStartTime);
                        LocalDateTime songStartTime = finalSceneStartTime;
                        for (SoundFragment song : songs) {
                            ScheduledSongEntry songEntry = new ScheduledSongEntry(song, songStartTime);
                            entry.addSong(songEntry);
                            songStartTime = songStartTime.plusSeconds(songEntry.getEstimatedDurationSeconds());
                        }
                        return entry;
                    });
            sceneUnis.add(sceneUni);
            sceneStartTime = sceneStartTime.plusSeconds(scene.getDurationSeconds());
        }

        return Uni.join().all(sceneUnis).andFailFast()
                .map(entries -> {
                    entries.forEach(this::addSceneSchedule);
                    return this;
                });
    }

    private Uni<List<SoundFragment>> fetchSongsForScene(Brand brand, Scene scene, ScheduleSongSupplier songSupplier) {
        int quantity = estimateSongsForScene(scene);
        PlaylistRequest playlistRequest = scene.getPlaylistRequest();

        if (playlistRequest == null) {
            return songSupplier.getSongsForBrand(brand.getId(), PlaylistItemType.SONG, quantity);
        }

        WayOfSourcing sourcing = playlistRequest.getSourcing();

        if (sourcing == WayOfSourcing.RANDOM) {
            return songSupplier.getSongsForBrand(brand.getId(), PlaylistItemType.SONG, quantity);
        }

        if (sourcing == WayOfSourcing.QUERY) {
            return songSupplier.getSongsByQuery(brand.getId(), playlistRequest, quantity);
        }

        if (sourcing == WayOfSourcing.STATIC_LIST) {
            return songSupplier.getSongsFromStaticList(playlistRequest.getSoundFragments(), quantity);
        }

        return songSupplier.getSongsForBrand(brand.getId(), PlaylistItemType.SONG, quantity);
    }

    private int estimateSongsForScene(Scene scene) {
        int durationSeconds = scene.getDurationSeconds();
        int avgSongDuration = 180;
        double djTalkRatio = scene.getTalkativity();
        int effectiveMusicTime = (int) (durationSeconds * (1 - djTalkRatio * 0.3));
        return Math.max(1, effectiveMusicTime / avgSongDuration);
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
        return sceneSchedules.get(sceneSchedules.size() - 1).getScheduledEndTime();
    }
}
