package io.kneo.broadcaster.service.chat;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolUseBlock;
import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.model.cnst.ChatType;
import io.kneo.broadcaster.service.chat.tools.AddToQueueTool;
import io.kneo.broadcaster.service.chat.tools.AddToQueueToolHandler;
import io.kneo.broadcaster.service.chat.tools.GetOnlineStations;
import io.kneo.broadcaster.service.chat.tools.GetOnlineStationsToolHandler;
import io.kneo.broadcaster.service.chat.tools.GetStations;
import io.kneo.broadcaster.service.chat.tools.GetStationsToolHandler;
import io.kneo.broadcaster.service.chat.tools.RadioStationControlTool;
import io.kneo.broadcaster.service.chat.tools.RadioStationControlToolHandler;
import io.kneo.broadcaster.service.chat.tools.SearchBrandSoundFragments;
import io.kneo.broadcaster.service.chat.tools.SearchBrandSoundFragmentsToolHandler;
import io.kneo.broadcaster.service.live.AiHelperService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

@ApplicationScoped
public class OwnerChatService extends ChatService {

    protected OwnerChatService() {
        super(null, null);
    }

    @Inject
    public OwnerChatService(BroadcasterConfig config, AiHelperService aiHelperService) {
        super(config, aiHelperService);
    }

    @Override
    protected ChatType getChatType() {
        return ChatType.OWNER;
    }

    @Override
    protected List<Tool> getAvailableTools() {
        return List.of(
                GetStations.toTool(),
                GetOnlineStations.toTool(),
                SearchBrandSoundFragments.toTool(),
                AddToQueueTool.toTool(),
                RadioStationControlTool.toTool()
        );
    }

    @Override
    protected MessageCreateParams buildMessageCreateParams(String renderedPrompt, List<MessageParam> history) {
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .maxTokens(1024L)
                .system(renderedPrompt)
                .messages(history)
                .model(Model.CLAUDE_HAIKU_4_5_20251001);
        
        for (Tool tool : getAvailableTools()) {
            builder.addTool(tool);
        }
        
        return builder.build();
    }

    @Override
    protected Uni<Void> handleToolCall(ToolUseBlock toolUse,
                                      Consumer<String> chunkHandler,
                                      Consumer<String> completionHandler,
                                      String connectionId,
                                      String brandName,
                                      long userId,
                                      List<MessageParam> conversationHistory) {

        Map<String, JsonValue> inputMap = extractInputMap(toolUse);
        Function<MessageCreateParams, Uni<Void>> streamFn =
                createStreamFunction(chunkHandler, completionHandler, connectionId, brandName, userId);

        if ("get_stations".equals(toolUse.name())) {
            return GetStationsToolHandler.handle(
                    toolUse, inputMap, aiHelperService, chunkHandler, connectionId, conversationHistory, followUpPrompt, streamFn
            );
        } else if ("get_online_stations".equals(toolUse.name())) {
            return GetOnlineStationsToolHandler.handle(
                    toolUse, inputMap, aiHelperService, waiter, chunkHandler, connectionId, conversationHistory, followUpPrompt, streamFn
            );
        } else if ("search_brand_sound_fragments".equals(toolUse.name())) {
            return SearchBrandSoundFragmentsToolHandler.handle(
                    toolUse, inputMap, aiHelperService, chunkHandler, connectionId, conversationHistory, followUpPrompt, streamFn
            );
        } else if ("add_to_queue".equals(toolUse.name())) {
            String djVoiceId = assistantNameByConnectionId.get(connectionId + "_voice");
            return AddToQueueToolHandler.handle(
                    toolUse, inputMap, queueService, elevenLabsClient, config, djVoiceId, chunkHandler, connectionId, conversationHistory, followUpPrompt, streamFn
            );
        } else if ("control_station".equals(toolUse.name())) {
            return RadioStationControlToolHandler.handle(
                    toolUse, inputMap, radioService, chunkHandler, connectionId, conversationHistory, followUpPrompt, streamFn
            );
        } else {
            return Uni.createFrom().failure(new IllegalArgumentException("Unknown tool: " + toolUse.name()));
        }
    }
}
