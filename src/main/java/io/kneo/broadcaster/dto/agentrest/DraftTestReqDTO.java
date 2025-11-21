package io.kneo.broadcaster.dto.agentrest;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.kneo.core.localization.LanguageCode;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DraftTestReqDTO {
    @NotNull
    private UUID songId;
    @NotNull
    private UUID agentId;
    @NotNull
    private UUID stationId;
    @NotNull
    private LanguageCode languageCode;
    @NotNull
    private String code;
}
