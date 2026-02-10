package io.kneo.broadcaster.model;

import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.SecureDataEntity;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Setter
@Getter
public class Listener extends SecureDataEntity<UUID> {
    private long userId;
    private EnumMap<LanguageCode, String> localizedName;
    private EnumMap<LanguageCode, Set<String>> nickName;
    private UserData userData;
    private Integer archived;
    private List<UUID> listenerOf;
    private List<UUID> labels;

    public Listener() {
        this.localizedName = new EnumMap<>(LanguageCode.class);
        this.nickName = new EnumMap<>(LanguageCode.class);
        this.labels = new ArrayList<>();
    }
}
