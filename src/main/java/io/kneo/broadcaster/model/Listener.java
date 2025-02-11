package io.kneo.broadcaster.model;

import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.SecureDataEntity;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.EnumMap;
import java.util.UUID;

@Setter
@Getter
@NoArgsConstructor
public class Listener extends SecureDataEntity<UUID> {
    private Long userId;
    private String country;
    private EnumMap<LanguageCode, String> localizedName = new EnumMap<>(LanguageCode.class);
    private EnumMap<LanguageCode, String> nickName = new EnumMap<>(LanguageCode.class);
    private String slugName;
    private Integer archived;
}
