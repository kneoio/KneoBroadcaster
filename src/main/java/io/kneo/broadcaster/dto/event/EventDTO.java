package io.kneo.broadcaster.dto.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.kneo.broadcaster.dto.ScenePromptDTO;
import io.kneo.broadcaster.dto.StagePlaylistDTO;
import io.kneo.broadcaster.dto.scheduler.ScheduleDTO;
import io.kneo.core.dto.AbstractDTO;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Setter
@Getter
@SuperBuilder
@NoArgsConstructor
public class EventDTO extends AbstractDTO {
    private String brand;
    private String brandId;
    @Pattern(regexp = "^[A-Za-z_]+/[A-Za-z_]+(?:/[A-Za-z_]+)?$", message = "Invalid timezone format")
    private String timeZone;
    private String type;
    @NotNull(message = "Description is required")
    private String description;
    private ScheduleDTO schedule;
    private String priority;
    private List<ScenePromptDTO> actions;
    private StagePlaylistDTO stagePlaylist;

}