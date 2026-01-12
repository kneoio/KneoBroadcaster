package io.kneo.broadcaster.service.chat.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;

import java.util.List;
import java.util.Map;

public class ListenerTool {

    public static Tool toTool() {
        Tool.InputSchema schema = Tool.InputSchema.builder()
                .properties(JsonValue.from(Map.of(
                        "action", Map.of(
                                "type", "string",
                                "enum", new String[]{"register", "list_listeners"},
                                "description", "Action to perform: 'register' to register current user as listener, 'list_listeners' to search/list all registered listeners"),
                        "nickname", Map.of(
                                "type", "string",
                                "description", "For 'register' action: Listener's preferred name or nickname (optional, defaults to email if not provided)"),
                        "search_term", Map.of(
                                "type", "string",
                                "description", "For 'list_listeners' action: Search term to filter listeners by name, nickname, email, telegram name, or slug (optional, returns all if not provided, max 100 results)"),
                        "listener_type", Map.of(
                                "type", "string",
                                "enum", new String[]{"OWNER", "TEMPORARY", "REGULAR", "CONTRIBUTOR", "GROUP"},
                                "description", "For 'list_listeners' action: Filter by listener type (optional)")
                )))
                .required(List.of("action"))
                .build();

        return Tool.builder()
                .name("listener")
                .description("Register the current user as a listener OR list/search all registered listeners. Use action='register' to register current user with the radio station. Use action='list_listeners' to search and view all registered listeners (max 100, only non-archived). To find the station owner, use action='list_listeners' with listener_type='OWNER'. Listeners can be searched by name, nickname, email, telegram name, or filtered by type.")
                .inputSchema(schema)
                .build();
    }
}
