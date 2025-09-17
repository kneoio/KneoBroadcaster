package io.kneo.broadcaster.dto.memory;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        property = "type",
        include = JsonTypeInfo.As.EXISTING_PROPERTY
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = EventDTO.class, name = "event"),
        @JsonSubTypes.Type(value = MessageDTO.class, name = "message")
})
public interface IMemoryContentDTO {
}