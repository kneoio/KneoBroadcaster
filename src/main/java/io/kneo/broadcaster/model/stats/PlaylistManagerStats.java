package io.kneo.broadcaster.model.stats;

import io.kneo.broadcaster.model.live.LiveSoundFragment;
import io.kneo.broadcaster.model.live.SongMetadata;
import io.kneo.broadcaster.model.cnst.SongSource;
import io.kneo.broadcaster.service.playlist.PlaylistManager;
import lombok.Builder;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Builder
public class PlaylistManagerStats {
    private List<SongMetadata> livePlaylist;
    private String brand;

    public static PlaylistManagerStats from(PlaylistManager playlistManager) {
        List<SongMetadata> allSongs = new ArrayList<>();

        playlistManager.getRegularQueue().stream()
                .map(LiveSoundFragment::getMetadata)
                .peek(md -> md.setSource(SongSource.REGULAR))
                .forEach(allSongs::add);

        playlistManager.getPrioritizedQueue().stream()
                .map(LiveSoundFragment::getMetadata)
                .peek(md -> md.setSource(SongSource.PRIORITIZED))
                .forEach(allSongs::add);

        playlistManager.getObtainedByHlsPlaylist().stream()
                .map(LiveSoundFragment::getMetadata)
                .peek(md -> md.setObtained(true))
                .forEach(allSongs::add);

        return PlaylistManagerStats.builder()
                .brand(playlistManager.getBrand())
                .livePlaylist(allSongs)
                .build();
    }
}
