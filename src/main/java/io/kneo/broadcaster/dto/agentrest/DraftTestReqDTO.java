package io.kneo.broadcaster.dto.agentrest;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;
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
    private String languageTag;
    @NotNull
    private String code;
    private Map<String, Object> userVariables;
}
