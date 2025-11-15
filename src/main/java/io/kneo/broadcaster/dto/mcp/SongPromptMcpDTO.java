package io.kneo.broadcaster.dto.mcp;

import io.kneo.broadcaster.model.ai.LlmType;
import io.kneo.broadcaster.model.ai.PromptType;
import io.kneo.broadcaster.model.ai.SearchEngineType;

import java.time.LocalTime;
import java.util.UUID;

public record SongPromptMcpDTO(
        UUID songId,
        String draft,
        String prompt,
        PromptType promptType,
        LlmType llmType,
        SearchEngineType searchEngineType,
        LocalTime startTime,
        boolean oneTimeRun,
        boolean dialogue
) {}
