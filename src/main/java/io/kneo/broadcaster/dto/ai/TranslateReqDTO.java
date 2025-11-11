package io.kneo.broadcaster.dto.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.kneo.broadcaster.dto.cnst.TranslationType;
import io.kneo.core.localization.LanguageCode;
import io.kneo.officeframe.cnst.CountryCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import java.util.UUID;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TranslateReqDTO {
    @NotBlank
    private String toTranslate;
    @NotNull
    private UUID masterId;
    @NotNull
    private TranslationType translationType;
    @NotNull
    private LanguageCode languageCode;
    private CountryCode countryCode;
}