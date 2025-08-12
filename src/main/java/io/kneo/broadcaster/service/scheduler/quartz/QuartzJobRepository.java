package io.kneo.broadcaster.service.scheduler.quartz;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class QuartzJobRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(QuartzJobRepository.class);

    @Inject
    PgPool client;

    public Uni<List<QuartzJobInfo>> findActiveJobs() {
        String sql = """
            SELECT 
                jd.job_name,
                jd.job_group,
                jd.description,
                t.trigger_name,
                t.trigger_group,
                t.trigger_state,
                t.next_fire_time,
                t.prev_fire_time,
                ct.cron_expression,
                ct.time_zone_id
            FROM qrtz_job_details jd
            JOIN qrtz_triggers t ON jd.sched_name = t.sched_name 
                AND jd.job_name = t.job_name 
                AND jd.job_group = t.job_group
            LEFT JOIN qrtz_cron_triggers ct ON t.sched_name = ct.sched_name 
                AND t.trigger_name = ct.trigger_name 
                AND t.trigger_group = ct.trigger_group
            WHERE jd.sched_name = 'KneoBroadcasterScheduler'
            ORDER BY t.next_fire_time ASC
            """;

        return client.query(sql)
                .execute()
                .onItem().transform(this::mapToJobInfoList)
                .onFailure().invoke(throwable -> 
                    LOGGER.error("Failed to query Quartz jobs", throwable));
    }

    public Uni<List<String>> findJobGroups() {
        String sql = """
            SELECT DISTINCT job_group 
            FROM qrtz_job_details 
            WHERE sched_name = 'KneoBroadcasterScheduler'
            ORDER BY job_group
            """;

        return client.query(sql)
                .execute()
                .onItem().transform(rowSet -> {
                    List<String> groups = new ArrayList<>();
                    for (Row row : rowSet) {
                        groups.add(row.getString("job_group"));
                    }
                    return groups;
                })
                .onFailure().invoke(throwable -> 
                    LOGGER.error("Failed to query job groups", throwable));
    }

    public Uni<Long> countJobsByState(String triggerState) {
        String sql = """
            SELECT COUNT(*) as job_count
            FROM qrtz_triggers 
            WHERE sched_name = 'KneoBroadcasterScheduler' 
            AND trigger_state = $1
            """;

        return client.preparedQuery(sql)
                .execute(io.vertx.mutiny.sqlclient.Tuple.of(triggerState))
                .onItem().transform(rowSet -> rowSet.iterator().next().getLong("job_count"))
                .onFailure().invoke(throwable -> 
                    LOGGER.error("Failed to count jobs by state: {}", triggerState, throwable));
    }

    private List<QuartzJobInfo> mapToJobInfoList(RowSet<Row> rowSet) {
        List<QuartzJobInfo> jobs = new ArrayList<>();
        for (Row row : rowSet) {
            QuartzJobInfo job = new QuartzJobInfo();
            job.setJobName(row.getString("job_name"));
            job.setJobGroup(row.getString("job_group"));
            job.setDescription(row.getString("description"));
            job.setTriggerName(row.getString("trigger_name"));
            job.setTriggerGroup(row.getString("trigger_group"));
            job.setTriggerState(row.getString("trigger_state"));
            job.setCronExpression(row.getString("cron_expression"));
            job.setTimeZoneId(row.getString("time_zone_id"));

            Long nextFireTime = row.getLong("next_fire_time");
            if (nextFireTime != null) {
                job.setNextFireTime(LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(nextFireTime), ZoneId.systemDefault()));
            }

            Long prevFireTime = row.getLong("prev_fire_time");
            if (prevFireTime != null) {
                job.setPrevFireTime(LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(prevFireTime), ZoneId.systemDefault()));
            }

            jobs.add(job);
        }
        return jobs;
    }

    public static class QuartzJobInfo {
        private String jobName;
        private String jobGroup;
        private String description;
        private String triggerName;
        private String triggerGroup;
        private String triggerState;
        private String cronExpression;
        private String timeZoneId;
        private LocalDateTime nextFireTime;
        private LocalDateTime prevFireTime;

        public String getJobName() { return jobName; }
        public void setJobName(String jobName) { this.jobName = jobName; }
        public String getJobGroup() { return jobGroup; }
        public void setJobGroup(String jobGroup) { this.jobGroup = jobGroup; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getTriggerName() { return triggerName; }
        public void setTriggerName(String triggerName) { this.triggerName = triggerName; }
        public String getTriggerGroup() { return triggerGroup; }
        public void setTriggerGroup(String triggerGroup) { this.triggerGroup = triggerGroup; }
        public String getTriggerState() { return triggerState; }
        public void setTriggerState(String triggerState) { this.triggerState = triggerState; }
        public String getCronExpression() { return cronExpression; }
        public void setCronExpression(String cronExpression) { this.cronExpression = cronExpression; }
        public String getTimeZoneId() { return timeZoneId; }
        public void setTimeZoneId(String timeZoneId) { this.timeZoneId = timeZoneId; }
        public LocalDateTime getNextFireTime() { return nextFireTime; }
        public void setNextFireTime(LocalDateTime nextFireTime) { this.nextFireTime = nextFireTime; }
        public LocalDateTime getPrevFireTime() { return prevFireTime; }
        public void setPrevFireTime(LocalDateTime prevFireTime) { this.prevFireTime = prevFireTime; }
    }
}
