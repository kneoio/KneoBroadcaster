package io.kneo.broadcaster.service.scheduler;

import io.kneo.broadcaster.dto.dashboard.JobExecutionDTO;
import io.kneo.broadcaster.dto.dashboard.ScheduledTaskDTO;
import io.kneo.broadcaster.dto.dashboard.SchedulerStatsDTO;
import io.kneo.broadcaster.model.Event;
import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.model.scheduler.Schedulable;
import io.kneo.broadcaster.model.scheduler.Schedule;
import io.kneo.broadcaster.model.scheduler.Task;
import io.kneo.broadcaster.repository.SchedulableRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.quartz.CronTrigger;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.impl.matchers.GroupMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@ApplicationScoped
public class SchedulerDataService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SchedulerDataService.class);
    private static final Duration CACHE_TTL = Duration.ofSeconds(10); // 10-second cache

    @Inject
    Scheduler scheduler;

    @Inject
    SchedulableRepositoryRegistry repositoryRegistry;

    // Cache variables
    private SchedulerStatsDTO cachedStats;
    private LocalDateTime lastCacheUpdate;

    public Uni<SchedulerStatsDTO> getSchedulerStats() {
        // Check if cache is still valid
        if (cachedStats != null && lastCacheUpdate != null &&
                Duration.between(lastCacheUpdate, LocalDateTime.now()).compareTo(CACHE_TTL) < 0) {
            LOGGER.debug("Returning cached scheduler stats");
            return Uni.createFrom().item(cachedStats);
        }

        // Fetch fresh data
        LOGGER.debug("Fetching fresh scheduler stats");
        return fetchFreshStats()
                .onItem().invoke(stats -> {
                    cachedStats = stats;
                    lastCacheUpdate = LocalDateTime.now();
                })
                .onFailure().invoke(throwable -> {
                    LOGGER.error("Failed to fetch fresh stats, clearing cache", throwable);
                    cachedStats = null;
                    lastCacheUpdate = null;
                });
    }

    private Uni<SchedulerStatsDTO> fetchFreshStats() {
        return Uni.createFrom().deferred(() -> {
            try {
                SchedulerStatsDTO stats = new SchedulerStatsDTO();

                stats.setSchedulerRunning(scheduler.isStarted() && !scheduler.isInStandbyMode());
                stats.setSchedulerName(scheduler.getSchedulerName());
                stats.setJobGroups(scheduler.getJobGroupNames());
                stats.setLastUpdated(LocalDateTime.now());

                Map<String, Integer> jobStateCounts = getJobStateCounts();
                stats.setActiveJobs(jobStateCounts.getOrDefault("ACTIVE", 0));
                stats.setPausedJobs(jobStateCounts.getOrDefault("PAUSED", 0));
                stats.setCompletedJobs(jobStateCounts.getOrDefault("COMPLETE", 0));
                stats.setErrorJobs(jobStateCounts.getOrDefault("ERROR", 0));

                return getScheduledTasks()
                        .onItem().transform(tasks -> {
                            stats.setTasks(tasks);
                            stats.setTotalScheduledTasks(tasks.size());
                            return stats;
                        });

            } catch (SchedulerException e) {
                LOGGER.error("Failed to get scheduler stats", e);
                throw new RuntimeException(e);
            }
        });
    }



    public Uni<List<ScheduledTaskDTO>> getScheduledTasks() {
        return Uni.createFrom().deferred(() -> {
            List<ScheduledTaskDTO> allTasks = new ArrayList<>();

            List<Uni<List<ScheduledTaskDTO>>> repositoryUnis = repositoryRegistry.getRepositories().stream()
                    .map(this::getTasksFromRepository)
                    .toList();

            return Uni.join().all(repositoryUnis).andFailFast()
                    .onItem().transform(results -> {
                        results.forEach(allTasks::addAll);
                        return allTasks;
                    });
        });
    }

    private Uni<List<ScheduledTaskDTO>> getTasksFromRepository(
            SchedulableRepository<? extends Schedulable> repository) {
        return repository.findActiveScheduled()
                .onItem().transform(entities ->
                        entities.stream()
                                .map(this::mapToDTO)
                                .toList()
                );
    }

    private ScheduledTaskDTO mapToDTO(Schedulable entity) {
        ScheduledTaskDTO dto = new ScheduledTaskDTO();

        dto.setEntityId(entity.getId().toString());
        dto.setEntityType(entity.getClass().getSimpleName());

        Schedule schedule = entity.getSchedule();
        if (schedule != null) {
            ZoneId entityTimeZone = schedule.getTimeZone();

            if (entity instanceof RadioStation station) {
                dto.setEntityName(station.getSlugName());
            } else if (entity instanceof Event event) {
                dto.setEntityName(event.getDescription());
            }

            dto.setEnabled(schedule.isEnabled());
            dto.setTimeZone(entityTimeZone.getId());

            if (!schedule.getTasks().isEmpty()) {
                Task firstTask = schedule.getTasks().get(0);
                dto.setTaskType(firstTask.getType() != null ? firstTask.getType().name() : "UNKNOWN");
                dto.setTriggerType(firstTask.getTriggerType() != null ? firstTask.getTriggerType().name() : "UNKNOWN");
            }

            List<JobExecutionDTO> executions = getJobExecutionsForEntity(entity);
            dto.setUpcomingExecutions(executions);

            if (!executions.isEmpty()) {
                dto.setNextExecution(executions.get(0).getScheduledTime().atZone(ZoneId.systemDefault()).withZoneSameInstant(entityTimeZone).toLocalDateTime());
                dto.setCronExpression(getCronExpressionForEntity(entity));
            }

           // LocalDateTime lastExecution = jobExecutionTracker.getLastExecution(entity.getId().toString());
           // if (lastExecution != null) {
           //     dto.setLastExecution(lastExecution.atZone(ZoneId.systemDefault()).withZoneSameInstant(entityTimeZone).toLocalDateTime());
           // }

            dto.setStatus(determineTaskStatus(dto, executions));
        } else {
            dto.setEnabled(false);
        }
        return dto;
    }

    private List<JobExecutionDTO> getJobExecutionsForEntity(Schedulable entity) {
        try {
            List<JobExecutionDTO> executions = new ArrayList<>();
            String entityId = entity.getId().toString();

            for (String groupName : scheduler.getJobGroupNames()) {
                Set<JobKey> jobKeys = scheduler.getJobKeys(GroupMatcher.jobGroupEquals(groupName));

                for (JobKey jobKey : jobKeys) {
                    if (jobKey.getName().contains(entityId) ||
                            (entity instanceof RadioStation station && jobKey.getName().contains(station.getSlugName()))) {

                        List<? extends Trigger> triggers = scheduler.getTriggersOfJob(jobKey);

                        for (Trigger trigger : triggers) {
                            JobExecutionDTO execution = new JobExecutionDTO();
                            execution.setJobName(jobKey.getName());

                            if (jobKey.getName().contains("_dj_start")) {
                                execution.setAction("START");
                            } else if (jobKey.getName().contains("_dj_stop")) {
                                execution.setAction("STOP");
                            } else if (jobKey.getName().contains("_dj_warning")) {
                                execution.setAction("WARNING");
                            } else if (jobKey.getName().contains("_event_trigger")) {
                                execution.setAction("EVENT_TRIGGER");
                            }

                            Date nextFireTime = trigger.getNextFireTime();
                            if (nextFireTime != null) {
                                execution.setScheduledTime(LocalDateTime.ofInstant(
                                        nextFireTime.toInstant(), ZoneId.systemDefault()));
                            }

                            Trigger.TriggerState state = scheduler.getTriggerState(trigger.getKey());
                            execution.setTriggerState(state.name());

                            executions.add(execution);
                        }
                    }
                }
            }

            return executions.stream()
                    .filter(e -> e.getScheduledTime() != null)
                    .sorted(Comparator.comparing(JobExecutionDTO::getScheduledTime))
                    .limit(5)
                    .collect(Collectors.toList());

        } catch (SchedulerException e) {
            LOGGER.error("Failed to get job executions for entity: {}", entity.getId(), e);
            return new ArrayList<>();
        }
    }

    private String getCronExpressionForEntity(Schedulable entity) {
        try {
            String entityId = entity.getId().toString();

            for (String groupName : scheduler.getJobGroupNames()) {
                Set<JobKey> jobKeys = scheduler.getJobKeys(GroupMatcher.jobGroupEquals(groupName));

                for (JobKey jobKey : jobKeys) {
                    if (jobKey.getName().contains(entityId) ||
                            (entity instanceof RadioStation station && jobKey.getName().contains(station.getSlugName()))) {

                        List<? extends Trigger> triggers = scheduler.getTriggersOfJob(jobKey);

                        for (Trigger trigger : triggers) {
                            if (trigger instanceof CronTrigger cronTrigger) {
                                return cronTrigger.getCronExpression();
                            }
                        }
                    }
                }
            }
        } catch (SchedulerException e) {
            LOGGER.error("Failed to get cron expression for entity: {}", entity.getId(), e);
        }

        return null;
    }

    private Map<String, Integer> getJobStateCounts() throws SchedulerException {
        Map<String, Integer> counts = new HashMap<>();
        counts.put("ACTIVE", 0);
        counts.put("PAUSED", 0);
        counts.put("COMPLETE", 0);
        counts.put("ERROR", 0);

        for (String groupName : scheduler.getJobGroupNames()) {
            Set<JobKey> jobKeys = scheduler.getJobKeys(GroupMatcher.jobGroupEquals(groupName));

            for (JobKey jobKey : jobKeys) {
                List<? extends Trigger> triggers = scheduler.getTriggersOfJob(jobKey);

                for (Trigger trigger : triggers) {
                    Trigger.TriggerState state = scheduler.getTriggerState(trigger.getKey());

                    switch (state) {
                        case NORMAL -> counts.put("ACTIVE", counts.get("ACTIVE") + 1);
                        case PAUSED -> counts.put("PAUSED", counts.get("PAUSED") + 1);
                        case COMPLETE -> counts.put("COMPLETE", counts.get("COMPLETE") + 1);
                        case ERROR, BLOCKED -> counts.put("ERROR", counts.get("ERROR") + 1);
                        case NONE -> {
                            // Job not scheduled or removed
                        }
                    }
                }
            }
        }

        return counts;
    }

    private String determineTaskStatus(ScheduledTaskDTO dto, List<JobExecutionDTO> executions) {
        if (!dto.isEnabled()) {
            return "DISABLED";
        }

        if (executions.isEmpty()) {
            return "NOT_SCHEDULED";
        }

        boolean hasActiveJobs = executions.stream()
                .anyMatch(job -> "NORMAL".equals(job.getTriggerState()));

        boolean hasErrorJobs = executions.stream()
                .anyMatch(job -> "ERROR".equals(job.getTriggerState()) ||
                        "BLOCKED".equals(job.getTriggerState()));

        if (hasErrorJobs) {
            return "ERROR";
        }

        return hasActiveJobs ? "ACTIVE" : "INACTIVE";
    }
}