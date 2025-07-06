package io.kneo.broadcaster.dto;

import io.kneo.core.dto.AbstractDTO;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Setter
@Getter
@NoArgsConstructor
public class ProfileDTO extends AbstractDTO {
    private String name;
    private String description;
    private boolean explicitContent;
    private Integer archived;
}