package io.kneo.broadcaster.model.memory;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.kneo.broadcaster.model.cnst.MemoryType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Memory {
    private UUID id;
    private String brand;
    private MemoryType memoryType;
    private LinkedHashMap<String, Object> content;
    private ZonedDateTime regDate;
    private ZonedDateTime lastModifiedDate;
}