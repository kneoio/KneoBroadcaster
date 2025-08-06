package io.kneo.broadcaster.dto.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.kneo.broadcaster.model.cnst.EventPriority;
import io.kneo.broadcaster.model.cnst.EventType;
import io.kneo.core.dto.AbstractDTO;
import io.kneo.core.dto.AbstractReferenceDTO;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import software.amazon.awssdk.services.s3.endpoints.internal.Value;

import java.time.LocalDateTime;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Setter
@Getter
@SuperBuilder
@NoArgsConstructor
public class EventDTO extends AbstractDTO {
    private String brand;

    private String type;

    @NotNull(message = "Timestamp is required")
    private LocalDateTime timestampEvent;

    @NotNull(message = "Description is required")
    private String description;
    private String priority;

}