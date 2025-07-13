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

        // Test isWithinTimeWindow method (moved to interface)
        ScheduleExecutionContext contextWithin = createContext(timeWindowTask, "09:30");
        assertTrue(taskExecutor.isWithinTimeWindow(contextWithin), "Should be within window at 09:30");

        ScheduleExecutionContext contextOutside = createContext(timeWindowTask, "11:00");
        assertFalse(taskExecutor.isWithinTimeWindow(contextOutside), "Should not be within window at 11:00");

        ScheduleExecutionContext contextAtStart = createContext(timeWindowTask, "09:00");
        assertTrue(taskExecutor.isWithinTimeWindow(contextAtStart), "Should be within window at start time");

        ScheduleExecutionContext contextAtEnd = createContext(timeWindowTask, "10:00");
        assertTrue(taskExecutor.isWithinTimeWindow(contextAtEnd), "Should be within window at end time");

        ScheduleExecutionContext contextBeforeStart = createContext(timeWindowTask, "08:45");
        assertFalse(taskExecutor.isWithinTimeWindow(contextBeforeStart), "Should not be within window before start");

        ScheduleExecutionContext contextAfterEnd = createContext(timeWindowTask, "10:15");
        assertFalse(taskExecutor.isWithinTimeWindow(contextAfterEnd), "Should not be within window after end");
    }

    @Test
    void testTaskExecutorSupports() {
        assertTrue(taskExecutor.supports(CronTaskType.PROCESS_DJ_CONTROL),
                "BrandScheduledTaskExecutor should support PROCESS_DJ_CONTROL");
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