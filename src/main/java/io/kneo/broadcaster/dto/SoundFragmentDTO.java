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
    private List<UUID> genres;
    private String album;
    private String slugName;
    private List<String> brands;
    private List<String> newlyUploaded;
    private List<UploadFileDTO> uploadedFiles;
    private List<UUID> representedInBrands;

    public SoundFragmentDTO(String id) {
        this.id = UUID.fromString(id);
    }
}