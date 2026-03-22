package io.kneo.broadcaster.model.soundfragment;

import com.semantyca.core.model.SecureDataEntity;
import com.semantyca.mixpla.model.cnst.SourceType;
import io.kneo.broadcaster.model.FileMetadata;
import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
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
    private List<UUID> genres;
    private List<UUID> labels;
    private String album;
    private String slugName;
    private Duration length;
    private String description;
    private int archived;
    private LocalDateTime expiresAt;
    private List<FileMetadata> fileMetadataList;
    private ContributionWorkflow contributionWorkflow;
    private Object addInfo;

    public String getMetadata() {
        return String.format("%s#%s", title, artist);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SoundFragment that = (SoundFragment) o;
        return Objects.equals(getId(), that.getId());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getId());
    }

    public Integer getDuration() {
        return 5;
    }
}