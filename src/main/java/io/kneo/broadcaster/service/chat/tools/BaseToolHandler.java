package io.kneo.broadcaster.service.chat.tools;

import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.ToolUseBlockParam;
import io.kneo.broadcaster.model.cnst.MessageType;
import io.vertx.core.json.JsonObject;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public abstract class BaseToolHandler {

    protected void sendProcessingChunk(Consumer<String> chunkHandler, String connectionId, String message) {
        JsonObject processing = new JsonObject()
                .put("type", MessageType.PROCESSING.name())
                .put("content", message)
                .put("username", "system")
                .put("connectionId", connectionId);
        chunkHandler.accept(processing.encode());
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
                .model(Model.CLAUDE_3_5_HAIKU_20241022)
                .build();
    }

    private JsonObject createMessage(MessageType type, String username, String content, long timestamp, String connectionId) {
        return new JsonObject()
                .put("type", type.name())
                .put("data", new JsonObject()
                        .put("id", UUID.randomUUID().toString())
                        .put("username", username)
                        .put("content", content)
                        .put("timestamp", timestamp)
                        .put("connectionId", connectionId)
                );
    }
}
