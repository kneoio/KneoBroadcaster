package io.kneo.broadcaster.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.kneo.core.dto.AbstractReferenceDTO;
import io.kneo.core.dto.validation.ValidCountry;
import io.kneo.core.localization.LanguageCode;
import io.kneo.officeframe.cnst.CountryCode;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.EnumMap;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Setter
@Getter
@SuperBuilder
@NoArgsConstructor
public class ListenerDTO extends AbstractReferenceDTO {
    long userId;

    @NotNull(message = "Country is required")
    @ValidCountry(message = "Country is not supported")
    private CountryCode country;

    private EnumMap<LanguageCode, String> nickName = new EnumMap<>(LanguageCode.class);
    private String slugName;
    private Integer archived;
    private List<UUID> listenerOf;

}