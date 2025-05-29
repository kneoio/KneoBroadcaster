package io.kneo.broadcaster.model;

import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.model.cnst.SourceType;
import io.kneo.core.model.SecureDataEntity;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.nio.file.Path;
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
    private String mimeType;
    private String doKey;
    private String description;
    @Deprecated
    /*
       the path should be taken from metadata directly
     */
    private Path filePath;
    //TODO to 2next
    private List<FileMetadata> fileMetadataList;
    private Integer archived;

    public String getMetadata() {
        return String.format("%s - %s", title, artist);
    }

}
