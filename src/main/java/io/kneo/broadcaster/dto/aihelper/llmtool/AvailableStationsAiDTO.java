package io.kneo.broadcaster.dto.aihelper.llmtool;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Setter
@Getter
public class AvailableStationsAiDTO {
    private List<RadioStationAiDTO> radioStations;
}