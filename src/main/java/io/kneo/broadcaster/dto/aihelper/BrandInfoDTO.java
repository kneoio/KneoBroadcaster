package io.kneo.broadcaster.dto.aihelper;

import io.kneo.broadcaster.dto.ai.LiveAgentDTO;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BrandInfoDTO {
    private String radioStationName;
    private RadioStationStatus radioStationStatus;
    private LiveAgentDTO agent;
}