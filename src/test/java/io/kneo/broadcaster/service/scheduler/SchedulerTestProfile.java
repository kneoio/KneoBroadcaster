package io.kneo.broadcaster.service.scheduler;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

public class SchedulerTestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("broadcaster.quarkus.file.upload.path", "/tmp");
    }
}