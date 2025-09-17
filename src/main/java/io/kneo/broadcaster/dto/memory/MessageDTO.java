package io.kneo.broadcaster.dto.memory;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MessageDTO implements IMemoryContentDTO {
    @NotBlank(message = "Sender is required")
    private String from;

    @NotBlank(message = "Message content is required")
    private String content;
}