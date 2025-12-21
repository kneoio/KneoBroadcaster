package io.kneo.broadcaster.service.chat.tools;

import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.ToolUseBlockParam;
import io.kneo.broadcaster.dto.ChatMessageDTO;

import java.util.List;
import java.util.function.Consumer;

public abstract class BaseToolHandler {

    protected void sendProcessingChunk(Consumer<String> chunkHandler, String connectionId, String message) {
        chunkHandler.accept(ChatMessageDTO.processing(message, connectionId).build().toJson());
    }

    protected void sendBotChunk(Consumer<String> chunkHandler, String connectionId, String username, String message) {
        chunkHandler.accept(ChatMessageDTO.bot(message, username, connectionId).build().toJson());
    }

    protected void addToolUseToHistory(ToolUseBlock toolUse, List<MessageParam> conversationHistory) {
        MessageParam assistantToolUseMsg = MessageParam.builder()
                .role(MessageParam.Role.ASSISTANT)
                .content(MessageParam.Content.ofBlockParams(
                        List.of(ContentBlockParam.ofToolUse(
                                ToolUseBlockParam.builder()
                                        .name(toolUse.name())
                                        .id(toolUse.id())
                                        .input(toolUse._input())
                                        .build()
                        ))
                ))
                .build();
        conversationHistory.add(assistantToolUseMsg);
    }

    protected void addToolResultToHistory(ToolUseBlock toolUse, String resultContent, List<MessageParam> conversationHistory) {
        MessageParam toolResultMsg = MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content(MessageParam.Content.ofBlockParams(
                        List.of(ContentBlockParam.ofToolResult(
                                ToolResultBlockParam.builder()
                                        .toolUseId(toolUse.id())
                                        .content(resultContent)
                                        .build()
                        ))
                ))
                .build();
        conversationHistory.add(toolResultMsg);
    }

    protected MessageCreateParams buildFollowUpParams(String systemPrompt, List<MessageParam> conversationHistory) {
        return MessageCreateParams.builder()
                .maxTokens(1024L)
                .system(systemPrompt)
                .messages(conversationHistory)
                .model(Model.CLAUDE_HAIKU_4_5_20251001)
                .build();
    }

}
