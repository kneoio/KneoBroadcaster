package io.kneo.broadcaster.dto.mcp;

import io.kneo.broadcaster.model.ai.LlmType;
import io.kneo.broadcaster.model.ai.PromptType;
import io.kneo.broadcaster.model.ai.SearchEngineType;

public record LivePromptMcpDTO(
        String draft,
        String prompt,
        PromptType promptType,
        LlmType llmType,
        SearchEngineType searchEngineType
) {}
