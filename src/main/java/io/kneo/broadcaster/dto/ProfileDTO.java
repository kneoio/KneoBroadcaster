package io.kneo.broadcaster.dto;

import io.kneo.broadcaster.model.Genre;
import io.kneo.core.dto.AbstractDTO;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Setter
@Getter
@NoArgsConstructor
public class ProfileDTO extends AbstractDTO {
    private String name;
    private String description;
    private List<Genre> allowedGenres;
    private boolean explicitContent;
    private Integer archived;
}