package io.kneo.broadcaster.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.kneo.broadcaster.model.cnst.MemoryType;
import io.kneo.core.dto.AbstractReferenceDTO;
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
public class MemoryDTO  extends AbstractReferenceDTO {
    private UUID id;
    @NotNull
    private String brand;
    @NotNull
    private MemoryType memoryType;
    @NotNull
    private JsonObject content;

}