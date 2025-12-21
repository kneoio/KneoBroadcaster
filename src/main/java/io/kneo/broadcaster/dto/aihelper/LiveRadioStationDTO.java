package io.kneo.broadcaster.dto.aihelper;

import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.dto.cnst.StreamType;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Setter
@Getter
public class LiveRadioStationDTO {
    private String name;
    private String slugName;
    private RadioStationStatus radioStationStatus;
    private StreamType streamType;
    private String djName;
    private TtsDTO tts;
    private List<SongPromptDTO> prompts;
    private String info;
}