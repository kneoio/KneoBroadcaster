package io.kneo.broadcaster.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;

import java.util.Map;

public class AddToQueueTool {

    public static Tool toTool() {
        Tool.InputSchema schema = Tool.InputSchema.builder()
                .properties(JsonValue.from(Map.of(
                        "brandName", Map.of(
                                "type", "string",
                                "description", "Brand (radio station) slug name to queue into"),
                        "textToTTSIntro", Map.of(
                                "type", "string",
                                "description", "Text to convert to speech for the intro"),
                        "soundFragments", Map.of(
                                "type", "object",
                                "additionalProperties", Map.of("type", "string"),
                                "description", "Optional map of fragment keys to UUIDs (as strings)"
                        )
                )))
                .build();

        return Tool.builder()
                .name("add_to_queue")
                .description("Queue audio for a brand using specified fragments")
                .inputSchema(schema)
                .build();
    }
}
