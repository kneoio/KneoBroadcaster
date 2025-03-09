package io.kneo.broadcaster.dto;

import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.core.dto.AbstractDTO;
import io.kneo.officeframe.cnst.CountryCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.net.URL;

@Setter
@Getter
@NoArgsConstructor
public class RadioStationDTO  extends AbstractDTO {
    private CountryCode country;
    private URL url;
    private URL actionUrl;
    private String slugName;
    private Integer archived;
    private int playlistCount;
    private int listenersCount;
    private RadioStationStatus status = RadioStationStatus.OFF_LINE;

}
