package io.kneo.broadcaster.model;

import io.kneo.core.model.SecureDataEntity;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

@Setter
@Getter
@NoArgsConstructor
public class ScriptScene extends SecureDataEntity<UUID> {
    private UUID scriptId;
    private String title;
    private List<UUID> prompts;
    private LocalTime startTime;
    private Integer archived;
    private boolean oneTimeRun;
    private List<Integer> weekdays;
}
