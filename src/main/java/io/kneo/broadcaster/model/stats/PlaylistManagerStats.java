package io.kneo.broadcaster.model.stats;

import io.kneo.broadcaster.service.radio.PlaylistManager;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class PlaylistManagerStats {
    private List<String> playedFragmentsList;
    private List<String> readyToPlayList;
    private String brand;
    private String currentlyPlaying;

    public static PlaylistManagerStats from(PlaylistManager playlistManager) {
        return PlaylistManagerStats.builder()
                .brand(playlistManager.getBrand())
                .readyToPlayList(playlistManager.getSegmentedAndreadyToConsumeByHlsPlaylist().stream()
                        .map(v -> v.getSoundFragment().getMetadata()).toList())
                        .playedFragmentsList(playlistManager.getObtainedByHlsPlaylist().stream()
                        .map(v -> v.getSoundFragment().getMetadata()).toList())
                .build();
    }
}