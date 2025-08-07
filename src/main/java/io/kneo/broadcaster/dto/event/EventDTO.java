package io.kneo.broadcaster.dto.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.kneo.broadcaster.dto.scheduler.ScheduleDTO;
import io.kneo.core.dto.AbstractDTO;
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
public class EventDTO extends AbstractDTO {
    private String brand;

    private String type;

    private LocalDateTime timestampEvent;

    @NotNull(message = "Description is required")
    private String description;
    private ScheduleDTO schedule;
    private String priority;

}