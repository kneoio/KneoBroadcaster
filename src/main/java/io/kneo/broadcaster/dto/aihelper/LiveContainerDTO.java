package io.kneo.broadcaster.dto.aihelper;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Setter
@Getter
public class LiveContainerDTO {
    private List<LiveRadioStationDTO> radioStations;
}