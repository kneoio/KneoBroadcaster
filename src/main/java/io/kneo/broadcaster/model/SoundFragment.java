package io.kneo.broadcaster.model;

import io.kneo.broadcaster.model.cnst.FragmentType;
import io.kneo.broadcaster.model.cnst.SourceType;
import io.kneo.core.model.SecureDataEntity;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Setter
@Getter
@NoArgsConstructor
public class SoundFragment extends SecureDataEntity<UUID> {
    private SourceType source;
    private int status;
    private int priority = 10;
    private int played;
    private FragmentType type;
    private String title;
    private String artist;
    private String genre;
    private String album;
    private byte[] file;
    private Integer archived;

}
