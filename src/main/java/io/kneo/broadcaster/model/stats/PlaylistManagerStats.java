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
        List<String> allReady = new java.util.ArrayList<>();

        playlistManager.getPrioritizedQueue().stream()
                .map(v -> v.getSoundFragment().getMetadata())
                .forEach(allReady::add);

        playlistManager.getRegularQueue().stream()
                .map(v -> v.getSoundFragment().getMetadata())
                .forEach(allReady::add);

        return PlaylistManagerStats.builder()
                .brand(playlistManager.getBrand())
                .readyToBeConsumed(allReady)
                .obtainedByPlaylist(playlistManager.getObtainedByHlsPlaylist().stream()
                        .map(v -> v.getSoundFragment().getMetadata()).toList())
                .build();
    }
}