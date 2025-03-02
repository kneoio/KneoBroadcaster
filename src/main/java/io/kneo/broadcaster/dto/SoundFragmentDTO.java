package io.kneo.broadcaster.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.kneo.broadcaster.model.cnst.FragmentType;
import io.kneo.broadcaster.model.cnst.SourceType;
import io.kneo.core.dto.AbstractDTO;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Setter
@Getter
@SuperBuilder
@NoArgsConstructor
public class SoundFragmentDTO extends AbstractDTO {
    private SourceType source = SourceType.LOCAL;
    private Integer status;
    private String localPath;
    private FragmentType type;
    private String title;
    private String artist;
    private String genre;
    private String album;
    private int priority = 999;

    public SoundFragmentDTO(String id) {
        this.id = UUID.fromString(id);
    }
}
