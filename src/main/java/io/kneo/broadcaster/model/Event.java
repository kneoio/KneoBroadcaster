package io.kneo.broadcaster.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.kneo.broadcaster.model.cnst.EventPriority;
import io.kneo.broadcaster.model.cnst.EventType;
import io.kneo.broadcaster.model.scheduler.Schedulable;
import io.kneo.broadcaster.model.scheduler.Scheduler;
import io.kneo.core.model.SecureDataEntity;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Event extends SecureDataEntity<UUID> implements Schedulable {
    private UUID brandId;
    private String brand;
    private ZoneId timeZone;
    private EventType type;
    private String description;
    private EventPriority priority;
    private Scheduler scheduler;
    private Integer archived;
    private List<Action> actions;

}