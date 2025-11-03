package io.kneo.broadcaster.model.stats;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.kneo.broadcaster.config.HlsPlaylistConfig;
import io.kneo.broadcaster.dto.live.LiveSoundFragmentDTO;
import io.kneo.broadcaster.model.cnst.SongSource;
import io.kneo.broadcaster.model.live.LiveSoundFragment;
import io.kneo.broadcaster.model.live.SongMetadata;
import io.kneo.broadcaster.service.playlist.PlaylistManager;
import jakarta.inject.Inject;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class PlaylistManagerStats {

    private List<LiveSoundFragmentDTO> livePlaylist;
    private List<LiveSoundFragmentDTO> queued;
    private String brand;
    private int duration;

    @Inject
    @JsonIgnore
    HlsPlaylistConfig hlsPlaylistConfig;

    public PlaylistManagerStats(PlaylistManager playlistManager, int duration) {
        this.brand = playlistManager.getBrand();
        this.livePlaylist = mapList(playlistManager.getPrioritizedQueue(), SongSource.PRIORITIZED);
        this.queued = mapList(playlistManager.getObtainedByHlsPlaylist(), SongSource.QUEUED);
        this.duration = duration;
    }

    private List<LiveSoundFragmentDTO> mapList(List<LiveSoundFragment> list, SongSource type) {
        return list.stream().map(live -> {
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
}
