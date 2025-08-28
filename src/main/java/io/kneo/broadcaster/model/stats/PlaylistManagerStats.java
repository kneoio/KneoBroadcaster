package io.kneo.broadcaster.model.stats;

import io.kneo.broadcaster.model.live.LiveSoundFragment;
import io.kneo.broadcaster.model.live.SongMetadata;
import io.kneo.broadcaster.service.playlist.PlaylistManager;
import lombok.Builder;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Builder
public class PlaylistManagerStats {
    private List<SongMetadata> obtainedByPlaylist;
    private List<SongMetadata> readyToBeConsumed;
    private String brand;

    public static PlaylistManagerStats from(PlaylistManager playlistManager) {
        List<SongMetadata> allReady = new ArrayList<>();

        playlistManager.getPrioritizedQueue().stream()
                .map(LiveSoundFragment::getMetadata)
                .forEach(allReady::add);

        playlistManager.getRegularQueue().stream()
                .map(LiveSoundFragment::getMetadata)
                .forEach(allReady::add);

        return PlaylistManagerStats.builder()
                .brand(playlistManager.getBrand())
                .readyToBeConsumed(allReady)
                .obtainedByPlaylist(playlistManager.getObtainedByHlsPlaylist().stream()
                        .map(LiveSoundFragment::getMetadata).toList())
                .build();
    }
}