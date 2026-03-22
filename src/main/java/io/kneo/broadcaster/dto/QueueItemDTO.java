package io.kneo.broadcaster.dto;

import com.semantyca.core.dto.AbstractDTO;
import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Setter @Getter @NoArgsConstructor
public class QueueItemDTO extends AbstractDTO {
    private String metadata;
    private PlaylistItemType type;
    private int priority;
}