package io.kneo.broadcaster.model.memory;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.kneo.broadcaster.model.cnst.MemoryType;
import io.kneo.core.model.DataEntity;
import io.vertx.core.json.JsonArray;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Memory extends DataEntity<UUID> {
    private String brand;
    private MemoryType memoryType;
    private JsonArray content;

}