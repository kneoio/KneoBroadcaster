package io.kneo.broadcaster.dto.aihelper;

import io.kneo.broadcaster.dto.cnst.StreamType;
import io.kneo.broadcaster.model.cnst.StreamStatus;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Setter
@Getter
public class LiveRadioStationDTO {
    private String name;
    private String slugName;
    private StreamStatus streamStatus;
    private StreamType streamType;
    private String djName;
    private String languageTag;
    private TtsDTO tts;
    private List<SongPromptDTO> prompts;
    private String info;
}