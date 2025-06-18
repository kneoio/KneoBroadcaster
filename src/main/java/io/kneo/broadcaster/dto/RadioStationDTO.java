package io.kneo.broadcaster.dto;

import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.model.cnst.ManagedBy;
import io.kneo.core.dto.AbstractDTO;
import io.kneo.officeframe.cnst.CountryCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.net.URL;
import java.time.ZoneId;
import java.util.UUID;

@Setter
@Getter
@NoArgsConstructor
public class RadioStationDTO extends AbstractDTO {
    private CountryCode country;
    private URL url;
    private ManagedBy managedBy;
    private URL iceCastUrl;
    private URL actionUrl;
    private String slugName;
    private ZoneId timeZone;
    private String color;
    private String description;
    private Integer archived;
    private int listenersCount;
    private RadioStationStatus status = RadioStationStatus.OFF_LINE;
    private UUID aiAgentId;
    private UUID profileId;
}