package io.kneo.broadcaster.dto;

import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.core.dto.AbstractDTO;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Setter @Getter @NoArgsConstructor
public class QueueItemDTO extends AbstractDTO {
    private String metadata;
    private PlaylistItemType type;
    private int priority;
}