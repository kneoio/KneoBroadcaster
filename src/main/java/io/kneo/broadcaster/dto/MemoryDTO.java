package io.kneo.broadcaster.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.kneo.broadcaster.model.cnst.MemoryType;
import io.vertx.core.json.JsonObject;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MemoryDTO {
    private UUID id;
    @NotNull
    private String brand;
    @NotNull
    private MemoryType memoryType;
    @NotNull
    private JsonObject content;

}