package io.kneo.broadcaster.dto.queue;

import io.kneo.broadcaster.dto.cnst.SSEProgressStatus;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SSEProgressDTO {
    private String id;
    private String name;
    private SSEProgressStatus status;
    private String errorMessage;
}