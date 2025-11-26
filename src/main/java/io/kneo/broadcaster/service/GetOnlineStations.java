package io.kneo.broadcaster.service;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;

import java.util.Map;

public class GetOnlineStations {

    public static Tool toTool() {
        Tool.InputSchema schema = Tool.InputSchema.builder()
                .properties(JsonValue.from(Map.of()))
                .build();

        return Tool.builder()
                .name("get_online_stations")
                .description("Get the current live status of online radio stations, including what's currently playing and DJ information")
                .inputSchema(schema)
                .build();
    }
}
