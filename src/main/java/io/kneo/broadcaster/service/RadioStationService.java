package io.kneo.broadcaster.service;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.dto.RadioStationDTO;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.dto.scheduler.OnceTriggerDTO;
import io.kneo.broadcaster.dto.scheduler.PeriodicTriggerDTO;
import io.kneo.broadcaster.dto.scheduler.ScheduleDTO;
import io.kneo.broadcaster.dto.scheduler.TaskDTO;
import io.kneo.broadcaster.dto.scheduler.TimeWindowTriggerDTO;
import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.model.cnst.ManagedBy;
import io.kneo.broadcaster.model.scheduler.OnceTrigger;
import io.kneo.broadcaster.model.scheduler.PeriodicTrigger;
import io.kneo.broadcaster.model.scheduler.Schedule;
import io.kneo.broadcaster.model.scheduler.Task;
import io.kneo.broadcaster.model.scheduler.TimeWindowTrigger;
import io.kneo.broadcaster.model.stats.BrandAgentStats;
import io.kneo.broadcaster.repository.RadioStationRepository;
import io.kneo.broadcaster.service.stream.RadioStationPool;
import io.kneo.broadcaster.util.WebHelper;
import io.kneo.core.dto.DocumentAccessDTO;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.IUser;
import io.kneo.core.model.user.SuperUser;
import io.kneo.core.service.AbstractService;
import io.kneo.core.service.UserService;
import io.kneo.officeframe.cnst.CountryCode;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.net.URL;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class RadioStationService extends AbstractService<RadioStation, RadioStationDTO> {
    private static final Logger LOGGER = LoggerFactory.getLogger(RadioStationService.class);

    private final RadioStationRepository repository;

    BroadcasterConfig broadcasterConfig;

    RadioStationPool radiostationPool;

    @Inject
    public RadioStationService(
            UserService userService,
            RadioStationRepository repository,
            RadioStationPool radiostationPool,
            BroadcasterConfig broadcasterConfig
    ) {
        super(userService);
        this.repository = repository;
        this.radiostationPool = radiostationPool;
        this.broadcasterConfig = broadcasterConfig;
    }

    public Uni<List<RadioStationDTO>> getAllDTO(final int limit, final int offset, final IUser user) {
        assert repository != null;
        return repository.getAll(limit, offset, false, user)
                .chain(list -> {
                    if (list.isEmpty()) {
                        return Uni.createFrom().item(List.of());
                    } else {
                        List<Uni<RadioStationDTO>> unis = list.stream()
                                .map(this::mapToDTO)
                                .collect(Collectors.toList());
                        return Uni.join().all(unis).andFailFast();
                    }
                });
    }

    public Uni<Integer> getAllCount(final IUser user) {
        assert repository != null;
        return repository.getAllCount(user, false);
    }

    public Uni<List<RadioStation>> getAll(final int limit, final int offset) {
        return repository.getAll(limit, offset, false, SuperUser.build());
    }

    public Uni<List<RadioStation>> getAll(final int limit, final int offset, IUser user) {
        return repository.getAll(limit, offset, false, user);
    }

    public Uni<RadioStation> getById(UUID id, IUser user) {
        return repository.findById(id, user, true);
    }

    public Uni<RadioStation> findByBrandName(String name) {
        return repository.findByBrandName(name);
    }


    @Override
    public Uni<Integer> delete(String id, IUser user) {
        assert repository != null;
        return repository.delete(UUID.fromString(id), user);
    }

    @Override
    public Uni<RadioStationDTO> getDTO(UUID id, IUser user, LanguageCode language) {
        assert repository != null;
        return repository.findById(id, user, false).chain(this::mapToDTO);
    }

    public Uni<BrandAgentStats> getStats(String stationName) {
        return repository.findStationStatsByStationName(stationName);
    }

    public Uni<RadioStationDTO> upsert(String id, RadioStationDTO dto, IUser user, LanguageCode code) {
        assert repository != null;
        RadioStation entity = buildEntity(dto);
        if (id == null) {
            return repository.insert(entity, user).chain(this::mapToDTO);
        } else {
            return repository.update(UUID.fromString(id), entity, user).chain(this::mapToDTO);
        }
    }

    public Uni<Integer> archive(String id, IUser user) {
        assert repository != null;
        return repository.findById(UUID.fromString(id), user, false)
                .chain(radioStation -> {
                    if (radioStation != null && radioStation.getSlugName() != null) {
                        return radiostationPool.stopAndRemove(radioStation.getSlugName())
                                .onFailure().invoke(failure ->
                                        LOGGER.warn("Failed to stop radio station {} during archive: {}",
                                                radioStation.getSlugName(), failure.getMessage()))
                                .onItem().ignore().andSwitchTo(
                                        repository.archive(UUID.fromString(id), user)
                                );
                    } else {
                        return repository.archive(UUID.fromString(id), user);
                    }
                });
    }

    private Uni<RadioStationDTO> mapToDTO(RadioStation doc) {
        return Uni.combine().all().unis(
                userService.getUserName(doc.getAuthor()),
                userService.getUserName(doc.getLastModifier()),
                radiostationPool.getLiveStatus(doc.getSlugName())
        ).asTuple().map(tuple -> {
            RadioStationDTO dto = new RadioStationDTO();
            dto.setId(doc.getId());
            dto.setAuthor(tuple.getItem1());
            dto.setRegDate(doc.getRegDate());
            dto.setLastModifier(tuple.getItem2());
            dto.setLastModifiedDate(doc.getLastModifiedDate());
            dto.setLocalizedName(doc.getLocalizedName());
            dto.setCountry(doc.getCountry().name());
            dto.setColor(doc.getColor());
            dto.setTimeZone(doc.getTimeZone().getId());
            dto.setDescription(doc.getDescription());
            dto.setSlugName(doc.getSlugName());
            dto.setManagedBy(doc.getManagedBy());
            dto.setBitRate(doc.getBitRate());
            dto.setAiAgentId(doc.getAiAgentId());
            dto.setProfileId(doc.getProfileId());

            ScheduleDTO scheduleDTO = new ScheduleDTO();
            Schedule schedule = doc.getSchedule();
            scheduleDTO.setEnabled(schedule.isEnabled());
            if (schedule.isEnabled()) {
                if (schedule.getTasks() != null && !schedule.getTasks().isEmpty()) {
                    List<TaskDTO> taskDTOs = schedule.getTasks().stream().map(task -> {
                        TaskDTO taskDTO = new TaskDTO();
                        task.setId(task.getId());
                        taskDTO.setType(task.getType());
                        taskDTO.setTarget(task.getTarget());
                        taskDTO.setTriggerType(task.getTriggerType());

                        if (task.getOnceTrigger() != null) {
                            OnceTriggerDTO onceTriggerDTO = new OnceTriggerDTO();
                            onceTriggerDTO.setStartTime(task.getOnceTrigger().getStartTime());
                            onceTriggerDTO.setDuration(task.getOnceTrigger().getDuration());
                            onceTriggerDTO.setWeekdays(task.getOnceTrigger().getWeekdays());
                            taskDTO.setOnceTrigger(onceTriggerDTO);
                        }

                        if (task.getTimeWindowTrigger() != null) {
                            TimeWindowTriggerDTO timeWindowTriggerDTO = new TimeWindowTriggerDTO();
                            timeWindowTriggerDTO.setStartTime(task.getTimeWindowTrigger().getStartTime());
                            timeWindowTriggerDTO.setEndTime(task.getTimeWindowTrigger().getEndTime());
                            timeWindowTriggerDTO.setWeekdays(task.getTimeWindowTrigger().getWeekdays());
                            taskDTO.setTimeWindowTrigger(timeWindowTriggerDTO);
                        }

                        if (task.getPeriodicTrigger() != null) {
                            PeriodicTriggerDTO periodicTriggerDTO = new PeriodicTriggerDTO();
                            periodicTriggerDTO.setStartTime(task.getPeriodicTrigger().getStartTime());
                            periodicTriggerDTO.setEndTime(task.getPeriodicTrigger().getEndTime());
                            periodicTriggerDTO.setInterval(task.getPeriodicTrigger().getInterval());
                            periodicTriggerDTO.setWeekdays(task.getPeriodicTrigger().getWeekdays());
                            taskDTO.setPeriodicTrigger(periodicTriggerDTO);
                        }
                        return taskDTO;
                    }).collect(Collectors.toList());

                    scheduleDTO.setTasks(taskDTOs);
                }
            }

            dto.setSchedule(scheduleDTO);

            try {
                dto.setHlsUrl(new URL(broadcasterConfig.getHost() + "/" + dto.getSlugName() + "/radio/stream.m3u8"));
                dto.setIceCastUrl(new URL(broadcasterConfig.getHost() + "/" + dto.getSlugName() + "/radio/icecast"));
                dto.setMixplaUrl(new URL("https://mixpla.kneo.io/?radio=" + dto.getSlugName()));
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }
            dto.setArchived(doc.getArchived());
            RadioStationStatus liveStatus = tuple.getItem3().getStatus();
            dto.setStatus(liveStatus);
            if (liveStatus == RadioStationStatus.ON_LINE
                    || liveStatus == RadioStationStatus.WARMING_UP
                    || liveStatus == RadioStationStatus.QUEUE_SATURATED
                    || liveStatus == RadioStationStatus.WAITING_FOR_CURATOR) {
                if (dto.getSchedule().isEnabled()) {
                    dto.setAiControlAllowed(tuple.getItem3().isAiControlAllowed());
                } else {
                    if (doc.getManagedBy() != ManagedBy.ITSELF) {
                        dto.setAiControlAllowed(true);
                    }
                }
            }
            return dto;
        });
    }

    private RadioStation buildEntity(RadioStationDTO dto) {
        RadioStation doc = new RadioStation();
        doc.setLocalizedName(dto.getLocalizedName());
        doc.setCountry(CountryCode.fromString(dto.getCountry()));
        doc.setArchived(dto.getArchived());
        doc.setManagedBy(dto.getManagedBy());
        doc.setColor(dto.getColor());
        doc.setDescription(dto.getDescription());
        doc.setTimeZone(ZoneId.of(dto.getTimeZone()));
        doc.setSlugName(WebHelper.generateSlug(dto.getLocalizedName()));
        doc.setBitRate(dto.getBitRate());
        doc.setAiAgentId(dto.getAiAgentId());
        doc.setProfileId(dto.getProfileId());

        if (dto.getSchedule() != null) {
            Schedule schedule = new Schedule();
            ScheduleDTO scheduleDTO = dto.getSchedule();
            schedule.setEnabled(scheduleDTO.isEnabled());
            schedule.setTimeZone(doc.getTimeZone());
            if (scheduleDTO.getTasks() != null && !scheduleDTO.getTasks().isEmpty()) {
                List<Task> tasks = scheduleDTO.getTasks().stream().map(taskDTO -> {
                    Task task = new Task();
                    task.setId(UUID.randomUUID());
                    task.setType(taskDTO.getType());
                    //TODO always default for now
                    task.setTarget("default");
                    task.setTriggerType(taskDTO.getTriggerType());

                    if (taskDTO.getOnceTrigger() != null) {
                        OnceTrigger onceTrigger = new OnceTrigger();
                        OnceTriggerDTO onceTriggerDTO = taskDTO.getOnceTrigger();
                        onceTrigger.setStartTime(normalizeTimeString(onceTriggerDTO.getStartTime()));
                        onceTrigger.setDuration(onceTriggerDTO.getDuration());
                        onceTrigger.setWeekdays(onceTriggerDTO.getWeekdays());
                        task.setOnceTrigger(onceTrigger);
                    }

                    if (taskDTO.getTimeWindowTrigger() != null) {
                        TimeWindowTrigger timeWindowTrigger = new TimeWindowTrigger();
                        TimeWindowTriggerDTO timeWindowTriggerDTO = taskDTO.getTimeWindowTrigger();
                        timeWindowTrigger.setStartTime(normalizeTimeString(timeWindowTriggerDTO.getStartTime()));
                        timeWindowTrigger.setEndTime(normalizeTimeString(timeWindowTriggerDTO.getEndTime()));
                        timeWindowTrigger.setWeekdays(timeWindowTriggerDTO.getWeekdays());
                        task.setTimeWindowTrigger(timeWindowTrigger);
                    }

                    if (taskDTO.getPeriodicTrigger() != null) {
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
            doc.setSchedule(schedule);
        }
        return doc;
    }

    private String normalizeTimeString(String timeString) {
        if ("24:00".equals(timeString)) {
            return "00:00";
        }
        return timeString;
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