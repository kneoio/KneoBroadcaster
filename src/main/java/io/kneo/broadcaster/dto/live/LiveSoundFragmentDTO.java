package io.kneo.broadcaster.dto.live;

import io.kneo.broadcaster.model.cnst.LiveSongSource;
import io.kneo.broadcaster.service.manipulation.mixing.MergingType;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LiveSoundFragmentDTO {
    private int duration;
    private String title;
    private String artist;
    private MergingType mergingType;
    private LiveSongSource queueType;
}