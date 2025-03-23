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

    private static String getCurrentPlaying(PlaylistManager playlistManager) {
        if (playlistManager.getCurrentlyPlaying() == null){
            return "NOT PLAYING";
        } else {
            return playlistManager.getCurrentlyPlaying().getSoundFragment().getMetadata();
        }
    }

    public static PlaylistManagerStats from(PlaylistManager playlistManager) {
        return PlaylistManagerStats.builder()
                .brand(playlistManager.getBrand())
                .readyToPlayList(playlistManager.getReadyToPlayList().stream()
                        .map(v -> v.getSoundFragment().getMetadata()).toList())
                .currentlyPlaying(getCurrentPlaying(playlistManager))
                .playedFragmentsList(playlistManager.getPlayedFragmentsList().stream()
                        .map(v -> v.getSoundFragment().getMetadata()).toList())
                .build();
    }
}