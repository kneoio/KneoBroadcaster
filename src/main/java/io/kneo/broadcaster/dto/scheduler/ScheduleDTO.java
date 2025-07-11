package io.kneo.broadcaster.dto.scheduler;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Setter
@Getter
public class ScheduleDTO {
    private boolean isEnabled;
    private List<TaskDTO> tasks;
}