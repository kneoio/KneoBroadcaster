package io.kneo.broadcaster.service.chat;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolUseBlock;
import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.dto.ListenerDTO;
import io.kneo.broadcaster.model.cnst.ChatType;
import io.kneo.broadcaster.model.cnst.ListenerType;
import io.kneo.broadcaster.service.ListenerService;
import io.kneo.broadcaster.service.chat.PublicChatSessionManager.VerificationResult;
import io.kneo.broadcaster.service.chat.tools.AddToQueueTool;
import io.kneo.broadcaster.service.chat.tools.AddToQueueToolHandler;
import io.kneo.broadcaster.service.chat.tools.GetOnlineStations;
import io.kneo.broadcaster.service.chat.tools.GetOnlineStationsToolHandler;
import io.kneo.broadcaster.service.chat.tools.GetStations;
import io.kneo.broadcaster.service.chat.tools.GetStationsToolHandler;
import io.kneo.broadcaster.service.chat.tools.SearchBrandSoundFragments;
import io.kneo.broadcaster.service.chat.tools.SearchBrandSoundFragmentsToolHandler;
import io.kneo.broadcaster.service.external.MailService;
import io.kneo.broadcaster.service.live.AiHelperService;
import io.kneo.broadcaster.util.ResourceUtil;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.AnonymousUser;
import io.kneo.core.model.user.IUser;
import io.kneo.core.model.user.SuperUser;
import io.kneo.core.repository.exception.ext.UserAlreadyExistsException;
import io.kneo.core.service.UserService;
import io.kneo.core.util.WebHelper;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

@ApplicationScoped
public class PublicChatService extends ChatService {

    protected PublicChatService() {
        super(null, null);
    }

    @Inject
    public PublicChatService(BroadcasterConfig config, AiHelperService aiHelperService) {
        super(config, aiHelperService);
    }

    @Inject
    MailService mailService;

    @Inject
    PublicChatSessionManager sessionManager;

    @Inject
    ListenerService listenerService;

    @Override
    protected ChatType getChatType() {
        return ChatType.PUBLIC;
    }

    @Inject
    UserService userService;

    @Inject
    PublicChatTokenService tokenService;

    public Uni<Void> sendCode(String email) {
        String code = sessionManager.generateAndStoreCode(email);
        return mailService.sendHtmlConfirmationCodeAsync(email, code).replaceWithVoid();
    }

    public VerificationResult verifyCode(String email, String code) {
        return sessionManager.verifyCode(email, code);
    }

    public Uni<RegistrationResult> registerListener(String sessionToken, String stationSlug, String nickname) {
        String email = sessionManager.validateSessionAndGetEmail(sessionToken);
        if (email == null) {
            return Uni.createFrom().failure(new IllegalArgumentException("Invalid or expired session"));
        }

        ListenerDTO dto = new ListenerDTO();
        dto.setEmail(email);
        dto.setListenerType(String.valueOf(ListenerType.REGULAR));
        dto.getLocalizedName().put(LanguageCode.en, email);
        if (nickname != null && !nickname.isBlank()) {
            dto.getNickName().put(LanguageCode.en, nickname);
        }

        return listenerService.upsertWithStationSlug(null, dto, stationSlug, ListenerType.REGULAR, SuperUser.build())
                .onFailure(UserAlreadyExistsException.class).recoverWithUni(throwable -> {
                    String slugName = WebHelper.generateSlug(nickname != null && !nickname.isBlank() ? nickname : email);
                    return userService.findByLogin(slugName)
                            .onItem().transformToUni(existingUser -> {
                                if (existingUser.getId() == 0) {
                                    return Uni.createFrom().failure(throwable);
                                }
                                ListenerDTO existingDto = new ListenerDTO();
                                existingDto.setUserId(existingUser.getId());
                                existingDto.setSlugName(slugName);
                                return Uni.createFrom().item(existingDto);
                            });
                })
                .onItem().transform(listenerDTO -> new RegistrationResult(
                        listenerDTO.getUserId(),
                        tokenService.generateToken(listenerDTO.getUserId(), listenerDTO.getSlugName())
                ));
    }

    public Uni<String> refreshToken(String oldToken) {
        PublicChatTokenService.TokenValidationResult result = tokenService.validateToken(oldToken);
        if (!result.valid()) {
            return Uni.createFrom().failure(new IllegalArgumentException("Invalid or expired token"));
        }
        return userService.findById(result.userId())
                .onItem().transform(userOptional -> {
                    if (userOptional.isEmpty()) {
                        throw new IllegalArgumentException("User not found");
                    }
                    IUser user = userOptional.get();
                    return tokenService.generateToken(user.getId(), user.getUserName());
                });
    }

    public Uni<IUser> authenticateUserFromToken(String token) {
        if (token == null || token.isBlank()) {
            return Uni.createFrom().failure(new IllegalArgumentException("Token is required"));
        }

        PublicChatTokenService.TokenValidationResult result = tokenService.validateToken(token);
        if (result.valid()) {
            return userService.findById(result.userId())
                    .onItem().transformToUni(userOptional -> {
                        if (userOptional.isEmpty()) {
                            return Uni.createFrom().failure(new IllegalArgumentException("User not found"));
                        }
                        return Uni.createFrom().item(userOptional.get());
                    });
        }

        String email = sessionManager.validateSessionAndGetEmail(token);
        if (email != null) {
            return Uni.createFrom().item(AnonymousUser.build());
        }

        return Uni.createFrom().failure(new IllegalArgumentException("Invalid or expired token"));
    }

    @Override
    protected List<Tool> getAvailableTools() {
        return List.of(
                GetStations.toTool(),
                GetOnlineStations.toTool(),
                SearchBrandSoundFragments.toTool(),
                AddToQueueTool.toTool()
        );
    }

    @Override
    protected MessageCreateParams buildMessageCreateParams(String renderedPrompt, List<MessageParam> history) {
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .maxTokens(1024L)
                .system(renderedPrompt)
                .messages(history)
                .model(Model.CLAUDE_3_5_HAIKU_20241022);
        
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
                    toolUse, inputMap, aiHelperService, chunkHandler, connectionId, conversationHistory, getFollowUpPrompt(), streamFn
            );
        } else if ("get_online_stations".equals(toolUse.name())) {
            return GetOnlineStationsToolHandler.handle(
                    toolUse, inputMap, aiHelperService, chunkHandler, connectionId, conversationHistory, getFollowUpPrompt(), streamFn
            );
        } else if ("search_brand_sound_fragments".equals(toolUse.name())) {
            return SearchBrandSoundFragmentsToolHandler.handle(
                    toolUse, inputMap, aiHelperService, chunkHandler, connectionId, conversationHistory, getFollowUpPrompt(), streamFn
            );
        } else if ("add_to_queue".equals(toolUse.name())) {
            String djVoiceId = assistantNameByConnectionId.get(connectionId + "_voice");
            return AddToQueueToolHandler.handle(
                    toolUse, inputMap, queueService, elevenLabsClient, config, djVoiceId, chunkHandler, connectionId, conversationHistory, getFollowUpPrompt(), streamFn
            );
        } else {
            return Uni.createFrom().failure(new IllegalArgumentException("Unknown tool: " + toolUse.name()));
        }
    }

    @Override
    protected String getMainPrompt() {
        try {
            String custom = ResourceUtil.loadResourceAsString("/prompts/publicMainPrompt.hbs");
            return !custom.isBlank() ? custom : super.getMainPrompt();
        } catch (Exception ignored) {
            return super.getMainPrompt();
        }
    }

    @Override
    protected String getFollowUpPrompt() {
        try {
            String custom = ResourceUtil.loadResourceAsString("/prompts/publicFollowUpPrompt.hbs");
            return !custom.isBlank() ? custom : super.getFollowUpPrompt();
        } catch (Exception ignored) {
            return super.getFollowUpPrompt();
        }
    }
}
