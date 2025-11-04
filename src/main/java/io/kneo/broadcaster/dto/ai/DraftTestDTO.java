package io.kneo.broadcaster.dto.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.kneo.core.localization.LanguageCode;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DraftTestDTO {
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
