package io.kneo.broadcaster.dto.scheduler;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Setter
@Getter
public class ScheduleDTO {
    private String timezone;
    private List<TaskDTO> tasks;
}