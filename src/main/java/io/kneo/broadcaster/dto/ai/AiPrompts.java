package io.kneo.broadcaster.dto.ai;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class AiPrompts {
    public static String getDecisionPrompt() {
        try (InputStream is = AiPrompts.class.getResourceAsStream("/decision_prompt.txt")) {
            if (is == null) {
                throw new RuntimeException("decision_prompt.txt not found in classpath");
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load decision prompt", e);
        }
    }
}