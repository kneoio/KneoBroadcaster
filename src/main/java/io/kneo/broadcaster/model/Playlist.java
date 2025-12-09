package io.kneo.broadcaster.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.kneo.broadcaster.model.cnst.WayOfSourcing;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Playlist {
    private WayOfSourcing sourcing;
    private String description;
    private boolean explicitContent;
}