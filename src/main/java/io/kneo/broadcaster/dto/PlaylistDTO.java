package io.kneo.broadcaster.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PlaylistDTO {
    @NotBlank
    private String name;
    @NotBlank
    private String description;
    private boolean explicitContent;
}