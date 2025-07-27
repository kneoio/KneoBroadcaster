package io.kneo.broadcaster.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.kneo.core.dto.AbstractReferenceDTO;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Setter
@Getter
@SuperBuilder
@NoArgsConstructor
public class EventDTO extends AbstractReferenceDTO {
    private String brand;

    @NotNull(message = "Type is required")
    private String type;

    @NotNull(message = "Timestamp is required")
    private LocalDateTime timestampEvent;

    @NotNull(message = "Description is required")
    private String description;
    private String priority;

}