package io.kneo.broadcaster.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;

import java.util.List;
import java.util.Map;

public class ListenerDataTool {

    public static Tool toTool() {
        Tool.InputSchema schema = Tool.InputSchema.builder()
                .properties(JsonValue.from(Map.of(
                        "action", Map.of(
                                "type", "string",
                                "enum", new String[]{"set", "get", "remove"},
                                "description", "Action to perform: 'set' to store user data, 'get' to retrieve user data, 'remove' to delete a specific field"),
                        "field_name", Map.of(
                                "type", "string",
                                "description", "The name of the data field (e.g., 'telegram_name', 'email', 'gender', 'location', 'hair_color', 'favorite_genre', etc.)"),
                        "field_value", Map.of(
                                "type", "string",
                                "description", "For 'set' action: The value to store for the field. Not used for 'get' or 'remove' actions")
                )))
                .required(List.of("action", "field_name"))
                .build();

        return Tool.builder()
                .name("listener_data")
                .description("Store, retrieve, or remove personalized metadata about the current listener. Use this to remember any information about the user that they share during conversation (e.g., telegram name, email, gender, location, preferences, favorite genres, personal details like hair color, etc.). This allows for highly personalized interactions across sessions. Always use this tool when a user shares personal information.")
                .inputSchema(schema)
                .build();
    }
}
