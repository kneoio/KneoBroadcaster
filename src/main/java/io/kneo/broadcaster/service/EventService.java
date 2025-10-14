package io.kneo.broadcaster.service;

import io.kneo.broadcaster.dto.event.EventDTO;
import io.kneo.broadcaster.dto.event.EventEntryDTO;
import io.kneo.broadcaster.dto.scheduler.OnceTriggerDTO;
import io.kneo.broadcaster.dto.scheduler.PeriodicTriggerDTO;
import io.kneo.broadcaster.dto.scheduler.ScheduleDTO;
import io.kneo.broadcaster.dto.scheduler.TaskDTO;
import io.kneo.broadcaster.model.Event;
import io.kneo.broadcaster.model.cnst.EventPriority;
import io.kneo.broadcaster.model.cnst.EventType;
import io.kneo.broadcaster.model.radiostation.RadioStation;
import io.kneo.broadcaster.model.scheduler.OnceTrigger;
import io.kneo.broadcaster.model.scheduler.PeriodicTrigger;
import io.kneo.broadcaster.model.scheduler.Scheduler;
import io.kneo.broadcaster.model.scheduler.Task;
import io.kneo.broadcaster.model.scheduler.TriggerType;
import io.kneo.broadcaster.repository.EventRepository;
import io.kneo.broadcaster.service.scheduler.quartz.QuartzSchedulerService;
import io.kneo.core.dto.DocumentAccessDTO;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.IUser;
import io.kneo.core.model.user.SuperUser;
import io.kneo.core.service.AbstractService;
import io.kneo.core.service.UserService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class EventService extends AbstractService<Event, EventDTO> {
    private static final Logger LOGGER = LoggerFactory.getLogger(EventService.class);
    private final EventRepository repository;
    private final RadioStationService radioStationService;

    @Inject
    private QuartzSchedulerService quartzSchedulerService;

    @Inject
    public EventService(UserService userService,
                        EventRepository repository,
                        RadioStationService radioStationService
    ) {
        super(userService);
        this.repository = repository;
        this.radioStationService = radioStationService;
    }

    public Uni<List<EventEntryDTO>> getAll(final int limit, final int offset, final IUser user) {
        assert repository != null;
        return repository.getAll(limit, offset, false, user)
                .chain(list -> {
                    if (list.isEmpty()) {
                        return Uni.createFrom().item(List.of());
                    } else {
                        List<Uni<EventEntryDTO>> unis = list.stream()
                                .map(this::mapToEntryDTO)
                                .collect(Collectors.toList());
                        return Uni.join().all(unis).andFailFast();
                    }
                });
    }

    public Uni<Integer> getAllCount(final IUser user) {
        assert repository != null;
        return repository.getAllCount(user, false);
    }

    @Override
    public Uni<EventDTO> getDTO(UUID uuid, IUser user, LanguageCode code) {
        assert repository != null;
        return repository.findById(uuid, user, false)
                .chain(this::mapToDTO);
    }

    public Uni<List<EventDTO>> getForBrand(String brandSlugName, int limit, final int offset, IUser user) {
        assert repository != null;
        return repository.findForBrand(brandSlugName, limit, offset, user, false)
                .chain(list -> {
                    if (list.isEmpty()) {
                        return Uni.createFrom().item(List.of());
                    } else {
                        List<Uni<EventDTO>> unis = list.stream()
                                .map(this::mapToDTO)
                                .collect(Collectors.toList());
                        return Uni.join().all(unis).andFailFast();
                    }
                });
    }

    public Uni<Integer> getCountForBrand(final String brandSlugName, final IUser user) {
        assert repository != null;
        return repository.findForBrandCount(brandSlugName, user, false);
    }

    public Uni<EventDTO> upsert(String id, EventDTO dto, IUser user) {
        assert repository != null;

        Event entity = buildEntity(dto);

        Uni<Event> saveOperation;
        if (id == null) {
            saveOperation = repository.insert(entity, user);
        } else {
            saveOperation = repository.update(UUID.fromString(id), entity, user);
        }

        return saveOperation.chain(savedEntity -> {
            quartzSchedulerService.removeForEntity(savedEntity);
            quartzSchedulerService.scheduleEntity(savedEntity);

            return Uni.createFrom().item(savedEntity);
        }).chain(this::mapToDTO);
    }

    public Uni<Integer> archive(String id, IUser user) {
        assert repository != null;
        return repository.archive(UUID.fromString(id), user);
    }

    @Override
    public Uni<Integer> delete(String id, IUser user) {
        assert repository != null;
        return repository.delete(UUID.fromString(id), user);
    }

    private Uni<EventEntryDTO> mapToEntryDTO(Event doc) {
        assert radioStationService != null;
        return radioStationService.getById(doc.getBrandId(), SuperUser.build())
                .onItem().transform(RadioStation::getSlugName)
                .onFailure().recoverWithItem("Unknown Brand")
                .map(brand -> new EventEntryDTO(
                        doc.getId(),
                        brand,
                        doc.getType().name(),
                        doc.getPriority().name(),
                        doc.getDescription()
                ));
    }

    private Uni<EventDTO> mapToDTO(Event doc) {
        assert radioStationService != null;
        return Uni.combine().all().unis(
                userService.getUserName(doc.getAuthor()),
                userService.getUserName(doc.getLastModifier()),
                radioStationService.getById(doc.getBrandId(), SuperUser.build())
        ).asTuple().map(tuple -> {
            EventDTO dto = new EventDTO();
            dto.setId(doc.getId());
            dto.setAuthor(tuple.getItem1());
            dto.setRegDate(doc.getRegDate());
            dto.setLastModifier(tuple.getItem2());
            dto.setLastModifiedDate(doc.getLastModifiedDate());
            dto.setBrandId(tuple.getItem3().getId().toString());
            dto.setBrand(tuple.getItem3().getSlugName());
            dto.setType(doc.getType().name());
            dto.setDescription(doc.getDescription());
            dto.setPriority(doc.getPriority().name());

            if (doc.getScheduler() != null) {
                ScheduleDTO scheduleDTO = new ScheduleDTO();
                Scheduler schedule = doc.getScheduler();
                scheduleDTO.setEnabled(schedule.isEnabled());
                if (schedule.isEnabled() && schedule.getTasks() != null && !schedule.getTasks().isEmpty()) {
                    List<TaskDTO> taskDTOs = schedule.getTasks().stream().map(task -> {
                        TaskDTO taskDTO = new TaskDTO();
                        taskDTO.setId(task.getId());
                        taskDTO.setType(task.getType());
                        taskDTO.setTarget(task.getTarget());
                        taskDTO.setTriggerType(task.getTriggerType());
                        dto.setTimeZone(schedule.getTimeZone().getId());

                        if (task.getTriggerType() == TriggerType.ONCE) {
                            OnceTriggerDTO onceTriggerDTO = new OnceTriggerDTO();
                            onceTriggerDTO.setStartTime(task.getOnceTrigger().getStartTime());
                            onceTriggerDTO.setDuration(task.getOnceTrigger().getDuration());
                            onceTriggerDTO.setWeekdays(task.getOnceTrigger().getWeekdays());
                            taskDTO.setOnceTrigger(onceTriggerDTO);
                        }

                        if (task.getTriggerType() == TriggerType.PERIODIC) {
                            PeriodicTriggerDTO periodicTriggerDTO = new PeriodicTriggerDTO();
                            PeriodicTrigger trigger = task.getPeriodicTrigger();
                            periodicTriggerDTO.setStartTime(trigger.getStartTime());
                            periodicTriggerDTO.setEndTime(trigger.getEndTime());
                            periodicTriggerDTO.setWeekdays(trigger.getWeekdays());
                            periodicTriggerDTO.setInterval(trigger.getInterval());
                            taskDTO.setPeriodicTrigger(periodicTriggerDTO);
                        }

                        return taskDTO;
                    }).collect(Collectors.toList());

                    scheduleDTO.setTasks(taskDTOs);
                }
                dto.setSchedule(scheduleDTO);
            }
            return dto;
        });
    }

    private String normalizeTimeString(String timeString) {
        if ("24:00".equals(timeString)) {
            return "00:00";
        }
        return timeString;
    }

    private Event buildEntity(EventDTO dto) {
        Event doc = new Event();
        doc.setBrandId(UUID.fromString(dto.getBrandId()));
        doc.setType(EventType.valueOf(dto.getType()));
        doc.setDescription(dto.getDescription());
        doc.setPriority(EventPriority.valueOf(dto.getPriority()));
        doc.setTimeZone(ZoneId.of(dto.getTimeZone()));
        if (dto.getSchedule() != null) {
            Scheduler schedule = new Scheduler();
            ScheduleDTO scheduleDTO = dto.getSchedule();
            schedule.setTimeZone(doc.getTimeZone());
            schedule.setEnabled(scheduleDTO.isEnabled());
            if (scheduleDTO.getTasks() != null && !scheduleDTO.getTasks().isEmpty()) {
                List<Task> tasks = scheduleDTO.getTasks().stream().map(taskDTO -> {
                    Task task = new Task();
                    task.setId(UUID.randomUUID());
                    task.setType(taskDTO.getType());
                    task.setTarget("default");
                    task.setTriggerType(taskDTO.getTriggerType());

                    if (taskDTO.getTriggerType() == TriggerType.ONCE) {
                        OnceTrigger onceTrigger = new OnceTrigger();
                        OnceTriggerDTO onceTriggerDTO = taskDTO.getOnceTrigger();
                        onceTrigger.setStartTime(normalizeTimeString(onceTriggerDTO.getStartTime()));
                        onceTrigger.setDuration(onceTriggerDTO.getDuration());
                        onceTrigger.setWeekdays(onceTriggerDTO.getWeekdays());
                        task.setOnceTrigger(onceTrigger);
                    }

                    if (taskDTO.getTriggerType() == TriggerType.PERIODIC) {
                        PeriodicTrigger periodicTrigger = new PeriodicTrigger();
                        PeriodicTriggerDTO periodicTriggerDTO = taskDTO.getPeriodicTrigger();
                        periodicTrigger.setStartTime(normalizeTimeString(periodicTriggerDTO.getStartTime()));
                        periodicTrigger.setEndTime(normalizeTimeString(periodicTriggerDTO.getEndTime()));
                        periodicTrigger.setInterval(periodicTriggerDTO.getInterval());
                        periodicTrigger.setWeekdays(periodicTriggerDTO.getWeekdays());
                        task.setPeriodicTrigger(periodicTrigger);
                    }

                    return task;
                }).collect(Collectors.toList());
                schedule.setTasks(tasks);
            }
            doc.setScheduler(schedule);
        }
        return doc;
    }

    public Uni<List<DocumentAccessDTO>> getDocumentAccess(UUID documentId, IUser user) {
        assert repository != null;
        return repository.getDocumentAccessInfo(documentId, user)
                .onItem().transform(accessInfoList ->
                        accessInfoList.stream()
                                .map(this::mapToDocumentAccessDTO)
                                .collect(Collectors.toList())
                );
    }
}