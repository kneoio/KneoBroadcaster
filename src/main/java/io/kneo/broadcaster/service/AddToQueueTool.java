package io.kneo.broadcaster.service;

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
                        "uploadId", Map.of(
                                "type", "string",
                                "description", "Client-provided upload/queue operation id for progress tracking"),
                        "mergingMethod", Map.of(
                                "type", "string",
                                "description", "Merging strategy. One of: INTRO_SONG, NOT_MIXED, SONG_INTRO_SONG, INTRO_SONG_INTRO_SONG, SONG_CROSSFADE_SONG"),
                        "priority", Map.of(
                                "type", "integer",
                                "description", "Queue priority (default 100)"),
                        "filePaths", Map.of(
                                "type", "object",
                                "additionalProperties", Map.of("type", "string"),
                                "description", "Optional map of file identifiers to absolute/remote paths"),
                        "soundFragments", Map.of(
                                "type", "object",
                                "additionalProperties", Map.of("type", "string"),
                                "description", "Optional map of fragment keys to UUIDs (as strings)"
                        )
                )))
                .build();

        return Tool.builder()
                .name("add_to_queue")
                .description("Queue audio for a brand using specified merging method and fragments")
                .inputSchema(schema)
                .build();
    }
}
