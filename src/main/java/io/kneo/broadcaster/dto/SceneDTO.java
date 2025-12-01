package io.kneo.broadcaster.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.kneo.core.dto.AbstractDTO;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

@Setter
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SceneDTO extends AbstractDTO {
    private UUID scriptId;
    private String title;
    private double talkativity;
    private double podcastMode;
    private List<ScenePromptDTO> prompts;
    private LocalTime startTime;
    private boolean oneTimeRun;
    private List<Integer> weekdays;
}
