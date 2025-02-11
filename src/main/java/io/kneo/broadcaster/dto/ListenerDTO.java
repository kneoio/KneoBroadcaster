package io.kneo.broadcaster.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.kneo.core.dto.AbstractReferenceDTO;
import io.kneo.core.localization.LanguageCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.EnumMap;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Setter
@Getter
@SuperBuilder
@NoArgsConstructor
public class ListenerDTO extends AbstractReferenceDTO {
    long userId;
    private String country;
    private EnumMap<LanguageCode, String> localizedName = new EnumMap<>(LanguageCode.class);
    private EnumMap<LanguageCode, String> nickName = new EnumMap<>(LanguageCode.class);
    private String slugName;
    private Integer archived;



}
