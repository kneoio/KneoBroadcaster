package io.kneo.broadcaster.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.model.cnst.SourceType;
import io.kneo.core.dto.AbstractDTO;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.List;
import java.util.UUID;

@Setter
@Getter
@SuperBuilder
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SoundFragmentDTO extends AbstractDTO {
    private SourceType source = SourceType.USERS_UPLOAD;
    private Integer status = -1;
    @NotNull
    private PlaylistItemType type;
    @NotBlank
    private String title;
    @NotBlank
    private String artist;
    private List<String> genres; // Changed from single genre to genres list
    private String album;
    private String slugName;
    private List<String> brands;
    private List<String> newlyUploaded;
    private List<UploadFileDTO> uploadedFiles;
    private List<UUID> representedInBrands;

    public SoundFragmentDTO(String id) {
        this.id = UUID.fromString(id);
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