package io.kneo.broadcaster.model;

import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.SecureDataEntity;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Map;
import java.util.UUID;

@Setter
@Getter
@NoArgsConstructor
public class Listener extends SecureDataEntity<UUID> {
    private Long userId;
    private String country;
    private Map<String, LanguageCode> locName;
    private Map<String, LanguageCode> nickName;
    private String slugName;
    private Integer archived;
}
