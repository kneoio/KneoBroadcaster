package io.kneo.broadcaster.dto.memory;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SongIntroduction implements IMemoryContentDTO {
    private UUID id;
    private UUID relevantSoundFragmentId;
    private String title;
    private String artist;
    private String introSpeech;
}
