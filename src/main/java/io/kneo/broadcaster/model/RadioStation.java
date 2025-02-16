package io.kneo.broadcaster.model;

import io.kneo.broadcaster.controller.stream.Playlist;
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
    private String brand;
    private Playlist playlist;
    private int listenersCount;
    private String slugName;
    private Integer archived;
    private CountryCode country;

}
