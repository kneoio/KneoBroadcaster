package io.kneo.broadcaster.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.kneo.core.dto.AbstractReferenceDTO;
import io.kneo.core.localization.LanguageCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Setter
@Getter
@SuperBuilder
@NoArgsConstructor
public class ListenerDTO extends AbstractReferenceDTO {
    long userId;
    private String country;
    private Map<String, LanguageCode> locName;
    private Map<String, LanguageCode> nickName;
    private String slugName;
    private Integer archived;



}
