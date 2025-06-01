package io.kneo.broadcaster.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.model.cnst.SourceType;
import io.kneo.core.dto.AbstractDTO;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Setter
@Getter
@SuperBuilder
@NoArgsConstructor
public class SoundFragmentDTO extends AbstractDTO {
    @Builder.Default
    private SourceType source = SourceType.LOCAL;
    private Integer status = -1;
    @NotNull
    private PlaylistItemType type;
    @NotBlank
    private String title;
    @NotBlank
    private String artist;
    @NotBlank
    private String genre;
    private String album;
    private List<String> newlyUploaded;
    private List<UploadFileDTO> uploadedFiles;

    public SoundFragmentDTO(String id) {
        this.id = UUID.fromString(id);
    }
}