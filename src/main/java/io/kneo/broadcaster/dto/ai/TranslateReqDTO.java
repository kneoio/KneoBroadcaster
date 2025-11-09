package io.kneo.broadcaster.dto.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.kneo.broadcaster.dto.cnst.TranslationType;
import io.kneo.core.localization.LanguageCode;
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
    private String toTranslate; // Text/code to translate
    @NotNull
    private UUID masterId; // Reference to the master draft to copy metadata from
    @NotNull
    private TranslationType translationType;
    @NotNull
    private LanguageCode languageCode; // Target language for translation
}