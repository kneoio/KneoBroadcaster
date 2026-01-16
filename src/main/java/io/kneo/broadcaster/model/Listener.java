package io.kneo.broadcaster.model;

import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.SecureDataEntity;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.EnumMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Setter
@Getter
@NoArgsConstructor
public class Listener extends SecureDataEntity<UUID> {
    private long userId;
    private EnumMap<LanguageCode, String> localizedName = new EnumMap<>(LanguageCode.class);
    private EnumMap<LanguageCode, Set<String>> nickName = new EnumMap<>(LanguageCode.class);
    private UserData userData;
    private String slugName;
    private Integer archived;
    private List<UUID> radioStations;
}
