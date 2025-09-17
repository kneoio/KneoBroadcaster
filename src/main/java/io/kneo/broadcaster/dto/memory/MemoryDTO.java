package io.kneo.broadcaster.dto.memory;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.kneo.broadcaster.model.cnst.MemoryType;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.ZonedDateTime;
import java.util.List;
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
    private String color;
    @NotNull
    private MemoryType memoryType;
    @NotNull
    @JsonIgnore   //since Jackson cant deserialize it properly, but we need strict typesation
    private List<IMemoryContentDTO> content;
    private ZonedDateTime regDate;
    private ZonedDateTime lastModifiedDate;
}