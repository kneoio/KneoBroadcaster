package io.kneo.broadcaster.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RadioStationStatusDTO {
    private String name;
    private String managedBy;
    private String djName;
    private String djPreferredLang;
    private String djStatus;
    private String currentStatus;
    private String countryCode;
    private String color;
    private String description;
}