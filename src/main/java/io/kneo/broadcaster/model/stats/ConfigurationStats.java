package io.kneo.broadcaster.model.stats;

import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

@Getter
@Setter
public class ConfigurationStats {
    private Map<String, Map<String, String>> configDetails = new HashMap<>();

    public void addConfig(String className, Map<String, String> details) {
        configDetails.put(className, details);
    }
}