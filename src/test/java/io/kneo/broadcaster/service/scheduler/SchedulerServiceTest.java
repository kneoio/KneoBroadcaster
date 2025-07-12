package io.kneo.broadcaster.service.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kneo.broadcaster.model.scheduler.Schedule;
import io.kneo.broadcaster.model.scheduler.Task;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(SchedulerTestProfile.class)
class SchedulerServiceTest {

    @Inject
    SchedulerService schedulerService;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    BrandScheduledTaskExecutor taskExecutor;

    @Test
    void testIsTaskDue_FromTestData() throws Exception {
        InputStream testDataStream = getClass().getResourceAsStream("/test.json");
        TestData testData = objectMapper.readValue(testDataStream, TestData.class);

        Schedule schedule = testData.getSchedule();

        for (Task task : schedule.getTasks()) {
            if ("09:00".equals(task.getTimeWindowTrigger().getStartTime()) &&
                    "10:00".equals(task.getTimeWindowTrigger().getEndTime())) {

                LocalDateTime now = LocalDateTime.of(2024, 1, 1, 9, 30);
                assertTrue(invokeIsTaskDue(task, "09:30", now));

                now = LocalDateTime.of(2024, 1, 1, 8, 30);
                assertFalse(invokeIsTaskDue(task, "08:30", now));
            }

            if ("23:00".equals(task.getTimeWindowTrigger().getStartTime()) &&
                    "00:00".equals(task.getTimeWindowTrigger().getEndTime())) {

                LocalDateTime now = LocalDateTime.of(2024, 1, 1, 23, 30);
                assertFalse(invokeIsTaskDue(task, "23:30", now),
                        "Current implementation doesn't support cross-midnight windows");

                now = LocalDateTime.of(2024, 1, 1, 22, 30);
                assertFalse(invokeIsTaskDue(task, "22:30", now));
            }
        }
    }

    @Test
    void testTaskExecutorTimeWindowMethods() throws Exception {
        InputStream testDataStream = getClass().getResourceAsStream("/test.json");
        TestData testData = objectMapper.readValue(testDataStream, TestData.class);

        Task timeWindowTask = testData.getSchedule().getTasks().get(0);
        ScheduleExecutionContext contextAtStart = createContext(timeWindowTask, "09:00");
        assertTrue(taskExecutor.isAtWindowStart(contextAtStart), "Should detect start at 09:00");

        ScheduleExecutionContext contextAfterStart = createContext(timeWindowTask, "09:15");
        assertTrue(taskExecutor.isAtWindowStart(contextAfterStart), "Should detect start even at 09:15 (robust)");

        ScheduleExecutionContext contextBeforeStart = createContext(timeWindowTask, "08:45");
        assertFalse(taskExecutor.isAtWindowStart(contextBeforeStart), "Should not detect start before 09:00");

        ScheduleExecutionContext contextAtEnd = createContext(timeWindowTask, "10:00");
        assertTrue(taskExecutor.isAtWindowEnd(contextAtEnd), "Should detect end at 10:00");

        ScheduleExecutionContext contextAfterEnd = createContext(timeWindowTask, "10:15");
        assertTrue(taskExecutor.isAtWindowEnd(contextAfterEnd), "Should detect end even at 10:15 (robust)");

        ScheduleExecutionContext contextBeforeEnd = createContext(timeWindowTask, "09:45");
        assertFalse(taskExecutor.isAtWindowEnd(contextBeforeEnd), "Should not detect end before 10:00");

        // Test within window
        ScheduleExecutionContext contextWithin = createContext(timeWindowTask, "09:30");
        assertTrue(taskExecutor.isWithinTimeWindow(contextWithin), "Should be within window at 09:30");

        ScheduleExecutionContext contextOutside = createContext(timeWindowTask, "11:00");
        assertFalse(taskExecutor.isWithinTimeWindow(contextOutside), "Should not be within window at 11:00");
    }

    private ScheduleExecutionContext createContext(Task task, String currentTime) {
        return new ScheduleExecutionContext(null, task, currentTime);
    }

    private boolean invokeIsTaskDue(Task task, String currentTime, LocalDateTime now) {
        try {
            java.lang.reflect.Method method = SchedulerService.class.getDeclaredMethod(
                    "isTaskDue", Task.class, String.class, String.class, LocalDateTime.class);
            method.setAccessible(true);
            return (boolean) method.invoke(schedulerService, task, currentTime, "MONDAY", now);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}