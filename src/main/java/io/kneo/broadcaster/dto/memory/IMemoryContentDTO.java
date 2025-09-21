package io.kneo.broadcaster.dto.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.UUID;

public interface IMemoryContentDTO {

    default void setId(UUID id) {
        // no-op
    }

    default UUID getId() {
        return null;
    }

    default String toJson(){
        try {
            return new ObjectMapper().writeValueAsString(this);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}