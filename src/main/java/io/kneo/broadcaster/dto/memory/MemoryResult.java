package io.kneo.broadcaster.dto.memory;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MemoryResult {
    private List<AudienceContextDTO> audienceContext;
    private List<ListenerContextDTO> listenerContext;
    private List<MessageDTO> messages;
    private List<EventInMemoryDTO> events;
    private List<SongIntroduction> conversationHistory;
}
