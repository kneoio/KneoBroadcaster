package io.kneo.broadcaster.service.scheduler;

public class TaskKeyGenerator {
    public static String generate(String brand, String taskType, String target) {
        return String.format("%s_%s_%s", brand, taskType, target);
    }

    public static String generateWarning(String baseKey) {
        return baseKey + "_warning";
    }
}
