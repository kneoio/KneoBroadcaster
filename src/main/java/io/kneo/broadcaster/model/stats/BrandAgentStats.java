package io.kneo.broadcaster.model.stats;

import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Setter
@Getter
public class BrandAgentStats {

    private Long id;
    private String stationName;
    private String userAgent;
    private Long accessCount;
    private OffsetDateTime lastAccessTime;

}