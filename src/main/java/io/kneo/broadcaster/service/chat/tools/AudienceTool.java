package io.kneo.broadcaster.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;

import java.util.Map;

public class AudienceTool {

    public static Tool toTool() {
        Tool.InputSchema schema = Tool.InputSchema.builder()
                .properties(JsonValue.from(Map.of(
                        "search_term", Map.of(
                                "type", "string",
                                "description", "Search term to filter listeners by name, nickname, email, or slug (optional, returns all if not provided, max 100 results)")
                )))
                .build();

        return Tool.builder()
                .name("listener")
                .description("List and search all registered listeners. Returns max 100 non-archived listeners. Listeners can be searched by name, nickname, email, or slug using search_term parameter.")
                .inputSchema(schema)
                .build();
    }
}
