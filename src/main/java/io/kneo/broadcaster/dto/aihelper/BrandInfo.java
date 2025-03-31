package io.kneo.broadcaster.dto.aihelper;

import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BrandInfo {
    private String radioStationName;
    private RadioStationStatus radioStationStatus;
}