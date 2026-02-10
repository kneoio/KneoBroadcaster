package io.kneo.broadcaster.dto.live;

import io.kneo.broadcaster.model.cnst.LiveSongSource;
import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LiveSoundFragmentDTO {
    private int duration;
    private String title;
    private String artist;
    private PlaylistItemType itemType;
    private LiveSongSource queueType;
}