package io.kneo.broadcaster.dto.aihelper;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LiveRadioStationStatAiDTO {
    private String slugName;
    private int currentListeners;
    private String currentlyPlaying;

}