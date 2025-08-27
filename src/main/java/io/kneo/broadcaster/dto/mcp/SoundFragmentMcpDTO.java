package io.kneo.broadcaster.dto.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.core.dto.AbstractDTO;
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
public class SoundFragmentMcpDTO extends AbstractDTO {
    private PlaylistItemType type;
    private String title;
    private String artist;
    private List<String> genres;
    private String album;
    private String slugName;
    private List<String> brands;
    private List<UUID> representedInBrands;

    public SoundFragmentMcpDTO(String id) {
        this.id = UUID.fromString(id);
    }
}