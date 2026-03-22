package io.kneo.broadcaster.dto.agentrest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.semantyca.officeframe.model.cnst.CountryCode;
import io.kneo.broadcaster.dto.cnst.TranslationType;
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
    private String languageTag;
    @NotNull
    private CountryCode countryCode;
    private double version;
}