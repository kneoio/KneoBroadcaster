package io.kneo.broadcaster.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.kneo.core.dto.AbstractReferenceDTO;
import io.kneo.core.localization.LanguageCode;
import io.kneo.officeframe.cnst.CountryCode;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.EnumMap;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Setter
@Getter
@SuperBuilder
@NoArgsConstructor
public class ListenerDTO extends AbstractReferenceDTO {
    long userId;
    @NotBlank
    private CountryCode country;
    private EnumMap<LanguageCode, String> nickName = new EnumMap<>(LanguageCode.class);
    private String slugName;
    private Integer archived;
    private List<RadioStationDTO> radioStations;

}