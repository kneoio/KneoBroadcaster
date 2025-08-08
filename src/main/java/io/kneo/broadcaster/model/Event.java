package io.kneo.broadcaster.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.kneo.broadcaster.model.cnst.EventPriority;
import io.kneo.broadcaster.model.cnst.EventType;
import io.kneo.broadcaster.model.scheduler.Schedulable;
import io.kneo.broadcaster.model.scheduler.Schedule;
import io.kneo.core.model.SecureDataEntity;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.ZoneId;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Event extends SecureDataEntity<UUID> implements Schedulable {
    private UUID brand;
    private ZoneId timeZone;
    private EventType type;
    private String description;
    private EventPriority priority;
    private Schedule schedule;
    private Integer archived;

}