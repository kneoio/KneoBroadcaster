package io.kneo.broadcaster.dto.memory;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.kneo.broadcaster.model.cnst.MemoryType;
import io.kneo.core.dto.AbstractReferenceDTO;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MemoryDTO<T> extends AbstractReferenceDTO {
    private UUID id;
    @NotNull
    private String brand;
    @NotNull
    private MemoryType memoryType;
    @NotNull
    T content;
}