package io.kneo.broadcaster.dto.dashboard;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AiDjStatsDTO {
    private UUID currentSceneId;
    private String currentSceneTitle;
    private LocalTime sceneStartTime;
    private LocalTime sceneEndTime;
    private int promptCount;
    private String nextSceneTitle;
    private LocalDateTime lastRequestTime;
    private String djName;
    private List<StatusMessage> messages;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StatusMessage {
        private MessageType type;
        private String message;
    }

    public enum MessageType {
        INFO,
        WARNING,
        ERROR
    }
}
