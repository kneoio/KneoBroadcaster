package io.kneo.broadcaster.model;

import io.kneo.broadcaster.model.ai.Prompt;
import io.kneo.core.model.SecureDataEntity;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Setter
@Getter
@NoArgsConstructor
public class ScriptScene extends SecureDataEntity<UUID> {
    private UUID scriptId;
    private String type;
    private List<Prompt> prompts;
    private LocalDateTime startTime;
    private Integer archived;
}
