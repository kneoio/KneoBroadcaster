package io.kneo.broadcaster.model.memory;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RadioEvent {
    private String type; // "achievement", "birthday", "morning_wake_up", etc.
    private String timestamp;
    private String description;
    private String priority; // "low", "medium", "high"
}
