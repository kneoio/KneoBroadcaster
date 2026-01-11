package io.kneo.broadcaster.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;

import java.util.Map;

public class RegisterListenerTool {

    public static Tool toTool() {
        Tool.InputSchema schema = Tool.InputSchema.builder()
                .properties(JsonValue.from(Map.of(
                        "nickname", Map.of(
                                "type", "string",
                                "description", "Listener's preferred name or nickname (optional, defaults to email if not provided)")
                )))
                .build();

        return Tool.builder()
                .name("register_listener")
                .description("Register the current user as a listener with the radio station so the DJ can remember them, mention them on air, send personalized messages, and maintain their listening history across sessions. User's email is already verified from their session.")
                .inputSchema(schema)
                .build();
    }
}
