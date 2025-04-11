package io.kneo.broadcaster.model.stats;

import io.kneo.broadcaster.service.radio.PlaylistManager;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class PlaylistManagerStats {
    private List<String> obtainedByPlaylist;
    private List<String> readyToBeConsumed;
    private String brand;

    public static PlaylistManagerStats from(PlaylistManager playlistManager) {
        return PlaylistManagerStats.builder()
                .brand(playlistManager.getBrand())
                .readyToBeConsumed(playlistManager.getSegmentedAndReadyToBeConsumed().stream()
                        .map(v -> v.getSoundFragment().getMetadata()).toList())
                        .obtainedByPlaylist(playlistManager.getObtainedByHlsPlaylist().stream()
                        .map(v -> v.getSoundFragment().getMetadata()).toList())
                .build();
    }
}