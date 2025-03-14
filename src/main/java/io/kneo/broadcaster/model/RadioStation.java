package io.kneo.broadcaster.model;

import io.kneo.broadcaster.controller.stream.HLSPlaylist;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.SecureDataEntity;
import io.kneo.officeframe.cnst.CountryCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.EnumMap;
import java.util.UUID;

@Setter
@Getter
@NoArgsConstructor
public class RadioStation extends SecureDataEntity<UUID> {
    private EnumMap<LanguageCode, String> localizedName = new EnumMap<>(LanguageCode.class);
    private HLSPlaylist playlist;
    private int listenersCount;
    private String slugName;
    private String primaryLang;
    private Integer archived;
    private CountryCode country;
    private RadioStationStatus status;
    private UUID profileId;
}