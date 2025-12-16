package io.kneo.broadcaster.model.stream;

import io.kneo.broadcaster.model.PlaylistRequest;
import io.kneo.broadcaster.model.Scene;
import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.model.cnst.SourceType;
import io.kneo.broadcaster.model.cnst.WayOfSourcing;
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

    private final WayOfSourcing sourcing;
    private final String playlistTitle;
    private final String artist;
    private final List<UUID> genres;
    private final List<UUID> labels;
    private final List<PlaylistItemType> playlistItemTypes;
    private final List<SourceType> sourceTypes;
    private final String searchTerm;
    private final List<UUID> soundFragments;

    public SceneScheduleEntry(Scene scene, LocalDateTime scheduledStartTime) {
        this.sceneId = scene.getId();
        this.sceneTitle = scene.getTitle();
        this.scheduledStartTime = scheduledStartTime;
        this.durationSeconds = scene.getDurationSeconds();
        this.songs = new ArrayList<>();

        PlaylistRequest pr = scene.getPlaylistRequest();
        if (pr != null) {
            this.sourcing = pr.getSourcing();
            this.playlistTitle = pr.getTitle();
            this.artist = pr.getArtist();
            this.genres = pr.getGenres();
            this.labels = pr.getLabels();
            this.playlistItemTypes = pr.getType();
            this.sourceTypes = pr.getSource();
            this.searchTerm = pr.getSearchTerm();
            this.soundFragments = pr.getSoundFragments();
        } else {
            this.sourcing = null;
            this.playlistTitle = null;
            this.artist = null;
            this.genres = null;
            this.labels = null;
            this.playlistItemTypes = null;
            this.sourceTypes = null;
            this.searchTerm = null;
            this.soundFragments = null;
        }
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
