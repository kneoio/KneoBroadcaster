package io.kneo.broadcaster.dto.memory;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InstantMessageDTO {
    private String content;
    private String sender;
    private String timestamp;
    private String location;
    private String reactionPriority;
}

// MemoryDTO<Map<String, LocationDTO>> listeners;
// MemoryDTO<AudienceContextDTO> audienceContext;
// MemoryDTO<List<ConversationHistoryDTO>> conversationHistory;
// MemoryDTO<List<EventDTO>> events;
// MemoryDTO<List<InstantMessageDTO>> instantMessages;

