package io.kneo.broadcaster.model.live;

import io.kneo.broadcaster.model.cnst.SongSource;
import io.kneo.broadcaster.service.manipulation.mixing.MergingType;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class SongMetadata {
    private UUID soundFragmentId;
    private String title;
    private String artist;
    private String album;
    private String genre;
    private MergingType mergingType;
    private String bitrate;
    private SongSource source;


    public SongMetadata(String title, String artist) {
        this.title = title;
        this.artist = artist;
    }

    public String toString() {
        return String.format("%s|%s", title, artist);
    }

}
