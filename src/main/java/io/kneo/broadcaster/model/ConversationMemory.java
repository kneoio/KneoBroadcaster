package io.kneo.broadcaster.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.kneo.broadcaster.model.cnst.MemoryType;
import io.kneo.core.model.DataEntity;
import io.vertx.core.json.JsonObject;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConversationMemory extends DataEntity<UUID> {
    private String brand;
    private MemoryType memoryType;
    private JsonObject content;
    private boolean archived;

}