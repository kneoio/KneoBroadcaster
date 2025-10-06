package io.kneo.broadcaster.dto.memory;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConversationHistoryDTO implements IMemoryContentDTO {
    private String title;
    private String artist;
    private String introSpeech;
    private HistoryStatus status;
}
