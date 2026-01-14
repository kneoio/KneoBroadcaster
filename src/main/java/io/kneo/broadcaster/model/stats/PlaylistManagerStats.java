package io.kneo.broadcaster.model.stats;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.kneo.broadcaster.config.HlsPlaylistConfig;
import io.kneo.broadcaster.dto.live.LiveSoundFragmentDTO;
import io.kneo.broadcaster.model.cnst.LiveSongSource;
import io.kneo.broadcaster.model.live.LiveSoundFragment;
import io.kneo.broadcaster.model.live.SongMetadata;
import io.kneo.broadcaster.model.soundfragment.SoundFragment;
import io.kneo.broadcaster.service.playlist.PlaylistManager;
import io.kneo.broadcaster.service.playlist.SongSupplier;
import jakarta.inject.Inject;
import lombok.Getter;
import lombok.Setter;

import java.util.Collection;
import java.util.List;

@Getter
@Setter
public class PlaylistManagerStats {

    private List<LiveSoundFragmentDTO> livePlaylist;
    private List<LiveSoundFragmentDTO> queued;
    private List<LiveSoundFragmentDTO> playedSongs;
    private String brand;
    private int duration;

    @Inject
    @JsonIgnore
    HlsPlaylistConfig hlsPlaylistConfig;

    public PlaylistManagerStats(PlaylistManager playlistManager, int duration, SongSupplier songSupplier) {
        this.brand = playlistManager.getBrandSlug();
        this.livePlaylist = mapList(playlistManager.getPrioritizedQueue(), LiveSongSource.PRIORITIZED);
        this.queued = mapList(playlistManager.getObtainedByHlsPlaylist(), LiveSongSource.QUEUED);
        this.playedSongs = mapPlayedSongs(songSupplier.getPlayedSongsForBrand(playlistManager.getBrandSlug()));
        this.duration = duration;

    }

    private List<LiveSoundFragmentDTO> mapList(Collection<LiveSoundFragment> list, LiveSongSource type) {
        List<LiveSoundFragment> ordered = List.copyOf(list);
        if (type == LiveSongSource.PRIORITIZED) {
            ordered = new java.util.ArrayList<>(ordered);
            java.util.Collections.reverse(ordered);
        }

        return ordered.stream().map(live -> {
            SongMetadata m = live.getMetadata();
            LiveSoundFragmentDTO dto = new LiveSoundFragmentDTO();
            dto.setTitle(m.getTitle());
            dto.setArtist(m.getArtist());
            dto.setMergingType(m.getMergingType());
            dto.setDuration(live.getSegments().values().iterator().next().size() * duration);
            dto.setQueueType(type);
            return dto;
        }).toList();
    }

    private List<LiveSoundFragmentDTO> mapPlayedSongs(List<SoundFragment> playedSongs) {
        return playedSongs.stream().map(fragment -> {
            LiveSoundFragmentDTO dto = new LiveSoundFragmentDTO();
            dto.setTitle(fragment.getTitle());
            dto.setArtist(fragment.getArtist());
            dto.setQueueType(LiveSongSource.PLAYED);
            if (fragment.getLength() != null) {
                dto.setDuration((int) fragment.getLength().toSeconds());
            }
            return dto;
        }).toList();
    }


}
