package io.kneo.broadcaster.model;

import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.model.cnst.SourceType;
import io.kneo.core.model.SecureDataEntity;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

@Setter
@Getter
@NoArgsConstructor
public class SoundFragment extends SecureDataEntity<UUID> {
    private SourceType source;
    private int status;
    private PlaylistItemType type;
    private String title;
    private String artist;
    private String genre;
    private String album;
    private String slugName;
    private Integer archived;
    private List<FileMetadata> fileMetadataList;
    //TODO we need to use
    private Object addInfo;

    public String getMetadata() {
        return String.format("%s#%s", title, artist);
    }

}
