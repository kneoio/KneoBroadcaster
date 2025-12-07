package io.kneo.broadcaster.model.chat;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.kneo.broadcaster.model.cnst.ChatType;
import io.kneo.broadcaster.model.cnst.MessageType;
import io.kneo.core.model.DataEntity;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatMessage extends DataEntity<UUID> {
    private Long userId;
    private ChatType chatType;
    private MessageType messageType;
    private String username;
    private String content;
    private String connectionId;
    private LocalDateTime timestamp;
}
