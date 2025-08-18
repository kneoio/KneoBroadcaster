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
    private List<String> genres; // Changed from single genre to genres list
    private String album;
    private String slugName;
    private Integer archived;
    private List<FileMetadata> fileMetadataList;
    private Object addInfo;

    public String getMetadata() {
        return String.format("%s#%s", title, artist);
    }

    // Backward compatibility method - returns first genre or null
    @Deprecated
    public String getGenre() {
        return (genres != null && !genres.isEmpty()) ? genres.get(0) : null;
    }

    // Backward compatibility method - sets as single-item list
    @Deprecated
    public void setGenre(String genre) {
        if (genre != null) {
            this.genres = List.of(genre);
        } else {
            this.genres = null;
        }
    }
}