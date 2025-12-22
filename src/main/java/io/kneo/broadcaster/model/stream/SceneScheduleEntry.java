package io.kneo.broadcaster.model.stream;

import io.kneo.broadcaster.model.PlaylistRequest;
import io.kneo.broadcaster.model.Scene;
import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.model.cnst.SourceType;
import io.kneo.broadcaster.model.cnst.WayOfSourcing;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.time.LocalTime;
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
    private final LocalTime originalStartTime;
    private final LocalTime originalEndTime;

    @Setter
    private LocalDateTime actualStartTime;
    @Setter
    private LocalDateTime actualEndTime;

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
        this.originalStartTime = scene.getStartTime();
        this.originalEndTime = null;

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

    public SceneScheduleEntry(UUID sceneId, String sceneTitle, LocalDateTime scheduledStartTime, int durationSeconds,
                              LocalTime originalStartTime, LocalTime originalEndTime,
                              WayOfSourcing sourcing, String playlistTitle, String artist,
                              List<UUID> genres, List<UUID> labels, List<PlaylistItemType> playlistItemTypes,
                              List<SourceType> sourceTypes, String searchTerm, List<UUID> soundFragments) {
        this.sceneId = sceneId;
        this.sceneTitle = sceneTitle;
        this.scheduledStartTime = scheduledStartTime;
        this.durationSeconds = durationSeconds;
        this.songs = new ArrayList<>();
        this.originalStartTime = originalStartTime;
        this.originalEndTime = originalEndTime;
        this.sourcing = sourcing;
        this.playlistTitle = playlistTitle;
        this.artist = artist;
        this.genres = genres;
        this.labels = labels;
        this.playlistItemTypes = playlistItemTypes;
        this.sourceTypes = sourceTypes;
        this.searchTerm = searchTerm;
        this.soundFragments = soundFragments;
    }

    public void addSong(ScheduledSongEntry song) {
        this.songs.add(song);
    }

    public LocalDateTime getScheduledEndTime() {
        return scheduledStartTime.plusSeconds(durationSeconds);
    }

    public boolean isActiveAt(LocalTime time, LocalTime nextSceneStartTime) {
        if (originalStartTime == null) {
            return false;
        }
        
        LocalTime effectiveEndTime = originalEndTime;
        if (effectiveEndTime == null) {
            effectiveEndTime = nextSceneStartTime;
        }
        
        if (effectiveEndTime == null) {
            return !time.isBefore(originalStartTime);
        }
        
        if (effectiveEndTime.isAfter(originalStartTime)) {
            return !time.isBefore(originalStartTime) && time.isBefore(effectiveEndTime);
        } else {
            return !time.isBefore(originalStartTime) || time.isBefore(effectiveEndTime);
        }
    }
}
