package io.kneo.broadcaster.dto.memory;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EventDTO implements IMemoryContentDTO {
    @NotBlank(message = "Event type is required")
    private String type;

    @NotBlank(message = "Timestamp is required")
    @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*", message = "Invalid timestamp format")
    private String timestamp;

    @NotBlank(message = "Description is required")
    private String description;

    @Pattern(regexp = "low|medium|high", message = "Priority must be low, medium, or high")
    private String priority;
}