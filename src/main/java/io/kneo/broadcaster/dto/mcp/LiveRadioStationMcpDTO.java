package io.kneo.broadcaster.dto.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.kneo.core.dto.AbstractDTO;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Setter
@Getter
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LiveRadioStationMcpDTO extends AbstractDTO {
    private String radioStationSlugName;
    private int queueSize;
}