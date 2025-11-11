package io.kneo.broadcaster.service;

import io.kneo.broadcaster.agent.AgentClient;
import io.kneo.broadcaster.dto.DraftDTO;
import io.kneo.broadcaster.dto.ai.PromptDTO;
import io.kneo.broadcaster.dto.ai.TranslateReqDTO;
import io.kneo.broadcaster.model.JobState;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.IUser;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.StringEscapeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Consumer;

@ApplicationScoped
public class TranslateService {
    private static final Logger LOGGER = LoggerFactory.getLogger(TranslateService.class);

    private final AgentClient agentClient;
    private final DraftService draftService;
    private final PromptService promptService;

    public record SseEvent(String type, JsonObject data) {}

    private final Map<String, JobState> jobs = new ConcurrentHashMap<>();
    private final Map<String, List<Consumer<SseEvent>>> subscribers = new ConcurrentHashMap<>();

    @Inject
    public TranslateService(AgentClient agentClient, DraftService draftService, PromptService promptService) {
        this.agentClient = agentClient;
        this.draftService = draftService;
        this.promptService = promptService;
    }

    public void subscribe(String jobId, Consumer<SseEvent> consumer) {
        subscribers.computeIfAbsent(jobId, k -> new ArrayList<>()).add(consumer);
        JobState st = jobs.get(jobId);
        if (st != null) {
            consumer.accept(new SseEvent("snapshot", new JsonObject()
                    .put("total", st.total)
                    .put("done", st.done)
                    .put("finished", st.finished)));
        }
    }

    public void unsubscribe(String jobId, Consumer<SseEvent> consumer) {
        List<Consumer<SseEvent>> list = subscribers.get(jobId);
        if (list != null) {
            list.remove(consumer);
            if (list.isEmpty()) subscribers.remove(jobId);
        }
    }

    private void emit(String jobId, String type, JsonObject data) {
        List<Consumer<SseEvent>> list = subscribers.get(jobId);
        if (list != null) {
            SseEvent ev = new SseEvent(type, data);
            for (Consumer<SseEvent> c : new ArrayList<>(list)) {
                try { c.accept(ev); } catch (Exception ignore) { }
            }
        }
    }

    public void startJobForDrafts(String jobId, List<TranslateReqDTO> dtos, IUser user) {
        startJob(jobId, dtos, user, this::translateAndUpsertDraft);
    }

    public void startJobForPrompts(String jobId, List<TranslateReqDTO> dtos, IUser user) {
        startJob(jobId, dtos, user, this::translateAndUpsertPrompt);
    }

    private <T> void startJob(String jobId, List<TranslateReqDTO> dtos, IUser user,
                              BiFunction<TranslateReqDTO, IUser, Uni<T>> translator) {
        if (jobId == null || jobId.isBlank()) throw new IllegalArgumentException("jobId is required");

        JobState st = new JobState();
        st.total = dtos != null ? dtos.size() : 0;
        st.done = 0;
        st.finished = false;
        jobs.put(jobId, st);

        emit(jobId, "started", new JsonObject().put("total", st.total));

        if (st.total == 0) {
            st.finished = true;
            emit(jobId, "done", new JsonObject().put("total", 0).put("success", 0));
            return;
        }

        processSequential(jobId, dtos, 0, user, translator)
                .subscribe().with(
                        ignored -> {},
                        err -> {
                            LOGGER.error("Translation job failed: {}", jobId, err);
                            st.finished = true;
                            emit(jobId, "error", new JsonObject().put("message", err.getMessage()));
                        }
                );
    }

    private <T> Uni<Void> processSequential(
            String jobId, List<TranslateReqDTO> dtos, int idx, IUser user,
            BiFunction<TranslateReqDTO, IUser, Uni<T>> translator) {

        if (idx >= dtos.size()) {
            JobState st = jobs.get(jobId);
            if (st != null) {
                st.finished = true;
                emit(jobId, "done", new JsonObject().put("total", st.total).put("success", st.done));
            }
            return Uni.createFrom().voidItem();
        }

        TranslateReqDTO dto = dtos.get(idx);
        LanguageCode lang = dto.getLanguageCode();

        return translator.apply(dto, user)
                .onItem().invoke(result -> {
                    JobState st = jobs.get(jobId);
                    if (st != null) st.done += 1;
                    JsonObject payload = new JsonObject()
                            .put("language", lang != null ? lang.name() : null)
                            .put("masterId", dto.getMasterId() != null ? dto.getMasterId().toString() : null)
                            .put("success", result != null);
                    emit(jobId, "language_done", payload);
                })
                .onFailure().invoke(err -> {
                    emit(jobId, "language_done", new JsonObject()
                            .put("language", lang != null ? lang.name() : null)
                            .put("masterId", dto.getMasterId() != null ? dto.getMasterId().toString() : null)
                            .put("success", false)
                            .put("message", err.getMessage()));
                })
                .onTermination().call(() -> processSequential(jobId, dtos, idx + 1, user, translator))
                .replaceWithVoid();
    }

    private Uni<DraftDTO> translateAndUpsertDraft(TranslateReqDTO dto, IUser user) {
        return draftService.getById(dto.getMasterId(), user)
                .chain(originalDraft -> {
                    if (originalDraft.getLanguageCode() == dto.getLanguageCode()) {
                        return Uni.createFrom().nullItem();
                    }

                    return agentClient.translate(dto.getToTranslate(), dto.getTranslationType(), dto.getLanguageCode())
                            .chain(resp -> {
                                String translatedContent = resp != null ? resp.getResult() : null;
                                if (translatedContent == null || translatedContent.isBlank()) {
                                    return Uni.createFrom().nullItem();
                                }
                                String newTitle = updateTitleWithLanguage(originalDraft.getTitle(), dto.getLanguageCode());

                                return draftService.findByMasterAndLanguage(dto.getMasterId(), dto.getLanguageCode(), false)
                                        .chain(existing -> {

                                            if (existing != null && !existing.isLocked()) {
                                                return Uni.createFrom().nullItem();
                                            }

                                            DraftDTO newDto = new DraftDTO();
                                            newDto.setTitle(newTitle);
                                            newDto.setContent(StringEscapeUtils.unescapeHtml4(translatedContent));
                                            newDto.setLanguageCode(dto.getLanguageCode());
                                            newDto.setMasterId(dto.getMasterId());
                                            newDto.setEnabled(true);
                                            newDto.setMaster(false);
                                            newDto.setLocked(true);

                                            String id = existing != null ? existing.getId().toString() : null;
                                            return draftService.upsert(id, newDto, user, dto.getLanguageCode());
                                        });
                            });
                });
    }

    private Uni<PromptDTO> translateAndUpsertPrompt(TranslateReqDTO dto, IUser user) {
        return promptService.getById(dto.getMasterId(), user)
                .chain(sourcePrompt -> {
                    if (sourcePrompt.getLanguageCode() == dto.getLanguageCode()) {
                        return Uni.createFrom().nullItem();
                    }

                    return agentClient.translate(dto.getToTranslate(), dto.getTranslationType(), dto.getLanguageCode())
                            .chain(resp -> {
                                String translatedContent = resp != null ? resp.getResult() : null;
                                if (translatedContent == null || translatedContent.isBlank()) {
                                    return Uni.createFrom().nullItem();
                                }
                                String newTitle = updateTitleWithLanguage(sourcePrompt.getTitle(), dto.getLanguageCode());

                                return promptService.findByMasterAndLanguage(dto.getMasterId(), dto.getLanguageCode(), false)
                                        .chain(existing -> {
                                            // If prompt exists but is not locked, skip it (user intentionally unlocked it)
                                            if (existing != null && !existing.isLocked()) {
                                                return Uni.createFrom().nullItem();
                                            }

                                            PromptDTO newDto = new PromptDTO();
                                            newDto.setTitle(newTitle);
                                            newDto.setPrompt(StringEscapeUtils.unescapeHtml4(translatedContent));
                                            newDto.setLanguageCode(dto.getLanguageCode());
                                            newDto.setEnabled(true);
                                            newDto.setMaster(false);
                                            newDto.setLocked(true);
                                            newDto.setMasterId(sourcePrompt.getId());
                                            //newDto.setDraftId(sourcePrompt.getDraftId());

                                            String id = existing != null ? existing.getId().toString() : null;
                                            return promptService.upsert(id, newDto, user);
                                        });
                            });
                });
    }

    private String updateTitleWithLanguage(String originalTitle, LanguageCode languageCode) {
        String titleWithoutSuffix = originalTitle.replaceAll("\\s*\\([a-z]{2}\\)\\s*$", "").trim();
        return titleWithoutSuffix + " (" + languageCode.name() + ")";
    }
}
