package io.kneo.broadcaster.dto.radiostation;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OneTimeStreamRunReqDTO {
    @NotNull
    private UUID brandId;
    @NotNull
    private UUID scriptId;
    @NotNull
    private Map<String, Object> userVariables;
}