package io.kneo.broadcaster.service;

import io.kneo.broadcaster.agent.AgentClient;
import io.kneo.broadcaster.dto.SceneDTO;
import io.kneo.broadcaster.dto.ScenePromptDTO;
import io.kneo.broadcaster.dto.memory.MemoryResult;
import io.kneo.broadcaster.model.Draft;
import io.kneo.broadcaster.model.JobState;
import io.kneo.broadcaster.model.Prompt;
import io.kneo.broadcaster.model.aiagent.AiAgent;
import io.kneo.broadcaster.model.aiagent.LlmType;
import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.model.radiostation.RadioStation;
import io.kneo.broadcaster.model.soundfragment.SoundFragment;
import io.kneo.broadcaster.service.live.DraftFactory;
import io.kneo.broadcaster.service.soundfragment.SoundFragmentService;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.IUser;
import io.kneo.core.model.user.SuperUser;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

@ApplicationScoped
public class ScriptDryRunService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ScriptDryRunService.class);

    private final AgentClient agentClient;
    private final ScriptService scriptService;
    private final SceneService sceneService;
    private final PromptService promptService;
    private final DraftService draftService;
    private final RadioStationService radioStationService;
    private final SoundFragmentService soundFragmentService;
    private final AiAgentService aiAgentService;
    private final DraftFactory draftFactory;

    public record SseEvent(String type, JsonObject data) {}
    private record ProcessContext(List<SceneDTO> scenes,
                                  RadioStation station, AiAgent agent, IUser user,
                                  StringBuilder scenarioBuilder, LlmType llmType,
                                  String agentName) {}

    private final Map<String, JobState> jobs = new ConcurrentHashMap<>();
    private final Map<String, List<Consumer<SseEvent>>> subscribers = new ConcurrentHashMap<>();

    @Inject
    public ScriptDryRunService(AgentClient agentClient, ScriptService scriptService, 
                               SceneService sceneService, PromptService promptService,
                               DraftService draftService, RadioStationService radioStationService,
                               SoundFragmentService soundFragmentService, AiAgentService aiAgentService,
                               DraftFactory draftFactory) {
        this.agentClient = agentClient;
        this.scriptService = scriptService;
        this.sceneService = sceneService;
        this.promptService = promptService;
        this.draftService = draftService;
        this.radioStationService = radioStationService;
        this.soundFragmentService = soundFragmentService;
        this.aiAgentService = aiAgentService;
        this.draftFactory = draftFactory;
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
            if (list.isEmpty()) {
                subscribers.remove(jobId);
            }
        }
    }

    private void emit(String jobId, String type, JsonObject data) {
        List<Consumer<SseEvent>> list = subscribers.get(jobId);
        if (list != null) {
            SseEvent ev = new SseEvent(type, data);
            for (Consumer<SseEvent> c : new ArrayList<>(list)) {
                try { 
                    c.accept(ev); 
                } catch (Exception ignore) { }
            }
        }
    }

    public void startDryRun(String jobId, UUID scriptId, UUID stationId, IUser user) {
        if (jobId == null || jobId.isBlank()) {
            throw new IllegalArgumentException("jobId is required");
        }

        JobState st = new JobState();
        st.total = 0;
        st.done = 0;
        st.finished = false;
        jobs.put(jobId, st);

        StringBuilder scenarioBuilder = new StringBuilder();

        scenarioBuilder.append("# Script Dry-Run Simulation\n");

        emit(jobId, "started", new JsonObject().put("message", "Starting dry-run simulation"));

        scriptService.getDTO(scriptId, user, LanguageCode.en)
                .chain(scriptDTO -> {
                    scenarioBuilder.append("Script: ").append(scriptDTO.getName()).append(" - ").append(scriptDTO.getDescription()).append("\n\n");
                    
                    return sceneService.getAllByScript(scriptId, 100, 0, user)
                            .map(scenes -> {
                                st.total = scenes.size();
                                emit(jobId, "total_scenes", new JsonObject().put("total", scenes.size()));
                                return scenes;
                            });
                })
                .chain(scenes -> radioStationService.getById(stationId, SuperUser.build())
                        .chain(station -> {
                            if (station.getAiAgentId() == null) {
                                return Uni.createFrom().item(new ProcessContext(scenes, station, null, user, scenarioBuilder, LlmType.OPENAI, "Default"));
                            }
                            return aiAgentService.getById(station.getAiAgentId(), user, LanguageCode.en)
                                    .map(agent -> new ProcessContext(scenes, station, agent, user, scenarioBuilder, agent.getLlmType(), agent.getName()));
                        }))
                .chain(context -> {
                    String stationName = null;
                    if (context.station.getLocalizedName() != null) {
                        stationName = context.station.getLocalizedName().get(LanguageCode.en);
                    }
                    if (stationName == null || stationName.isBlank()) {
                        stationName = context.station.getSlugName() != null ? context.station.getSlugName() : String.valueOf(context.station.getId());
                    }
                    context.scenarioBuilder.append("Station: ").append(stationName).append(" | ");
                    context.scenarioBuilder.append("Started: ")
                            .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                            .append("\n\n");
                    context.scenarioBuilder.append("DJ: ").append(context.agentName).append("\n\n");
                    return processScenes(jobId, context, 0);
                })
                .subscribe().with(
                        ignored -> {
                            st.finished = true;
                            String markdown = scenarioBuilder.toString();
                            emit(jobId, "done", new JsonObject()
                                    .put("total", st.total)
                                    .put("success", st.done)
                                    .put("scenario", markdown));
                        },
                        err -> {
                            LOGGER.error("Dry-run job failed: {}", jobId, err);
                            st.finished = true;
                            emit(jobId, "error", new JsonObject().put("message", err.getMessage()));
                        }
                );
    }



    private Uni<Void> processScenes(String jobId, ProcessContext context, int idx) {
        if (idx >= context.scenes.size()) {
            return Uni.createFrom().voidItem();
        }

        var sceneDTO = context.scenes.get(idx);
        JobState st = jobs.get(jobId);
        StringBuilder scenarioBuilder = context.scenarioBuilder;

        scenarioBuilder.append("## Scene ").append(idx + 1).append(": ").append(sceneDTO.getTitle()).append("\n");
        scenarioBuilder.append("Start Time: ").append(sceneDTO.getStartTime() != null ? sceneDTO.getStartTime().toString() : "N/A").append("\n");

        return simulateTimeProgression(scenarioBuilder)
                .chain(() -> maybeInsertSongIntro(context.station.getId(), scenarioBuilder))
                .chain(() -> processScenePrompts(jobId, sceneDTO, context, scenarioBuilder))
                .chain(() -> {
                    if (st != null) st.done += 1;
                    emit(jobId, "scene_done", new JsonObject()
                            .put("sceneIndex", idx)
                            .put("sceneTitle", sceneDTO.getTitle())
                            .put("done", st != null ? st.done : 0));
                    
                    scenarioBuilder.append("\n---\n\n");
                    return processScenes(jobId, context, idx + 1);
                });
    }

    private Uni<Void> processScenePrompts(String jobId, SceneDTO sceneDTO,
                                           ProcessContext context, StringBuilder scenarioBuilder) {
        if (sceneDTO.getPrompts() == null || sceneDTO.getPrompts().isEmpty()) {
            scenarioBuilder.append("*No prompts configured for this scene.*\n\n");
            return Uni.createFrom().voidItem();
        }

        return processPromptSequentially(jobId, sceneDTO.getPrompts(), 0, context, scenarioBuilder);
    }

    private Uni<Void> processPromptSequentially(String jobId, List<ScenePromptDTO> scenePrompts, int idx,
                                                 ProcessContext context, StringBuilder scenarioBuilder) {
        if (idx >= scenePrompts.size()) {
            return Uni.createFrom().voidItem();
        }

        ScenePromptDTO scenePrompt = scenePrompts.get(idx);
        UUID promptId = scenePrompt.getPromptId();
        
        return promptService.getById(promptId, context.user)
                .chain(prompt -> {
                    if (prompt.getDraftId() == null) {
                        scenarioBuilder.append("**Prompt ").append(idx + 1).append(":** ").append(prompt.getTitle()).append(" *(No draft linked)*\n\n");
                        return Uni.createFrom().voidItem();
                    }
                    
                    return draftService.getById(prompt.getDraftId(), context.user)
                            .chain(draft -> testPromptWithDraft(jobId, prompt, draft, context, scenarioBuilder, idx + 1));
                })
                .chain(() -> processPromptSequentially(jobId, scenePrompts, idx + 1, context, scenarioBuilder));
    }

    private Uni<Void> testPromptWithDraft(String jobId, Prompt prompt, Draft draft, 
                                           ProcessContext context, StringBuilder scenarioBuilder, int promptNumber) {
        scenarioBuilder.append("Prompt ").append(promptNumber).append(": ").append(prompt.getTitle()).append(" | ");
        scenarioBuilder.append("Draft: ").append(draft.getTitle()).append("\n");

        return soundFragmentService.getByTypeAndBrand(PlaylistItemType.SONG, context.station.getId())
                .onItem().transform(songs -> songs != null && !songs.isEmpty() ? songs.get(ThreadLocalRandom.current().nextInt(songs.size())) : null)
                .chain(song -> {
                    if (song == null) {
                        String warnMsg = String.format("No songs available for station '%s' (ID: %s). Draft template may have unresolved placeholders.",
                                context.station.getSlugName(), context.station.getId());
                        LOGGER.warn(warnMsg);
                        emit(jobId, "warning", new JsonObject()
                                .put("message", warnMsg)
                                .put("promptTitle", prompt.getTitle())
                                .put("draftTitle", draft.getTitle()));
                    }

                    MemoryResult emptyMemory = new MemoryResult();
                    emptyMemory.setConversationHistory(List.of());
                    emptyMemory.setMessages(List.of());
                    emptyMemory.setEvents(List.of());
                    
                    return draftFactory.createDraftFromCode(draft.getContent(), song, context.agent, context.station, LanguageCode.en, null)
                            .onFailure().invoke(err -> {
                                assert song != null;
                                String errorDetail = String.format("Failed to render draft template. Prompt: '%s', Draft: '%s', Station: '%s', Song: %s, Agent: %s. Error: %s",
                                        prompt.getTitle(),
                                        draft.getTitle(),
                                        context.station.getSlugName(),
                                        song.getTitle(),
                                        context.agent.getName(),
                                        err.getMessage());
                                LOGGER.error(errorDetail, err);
                                emit(jobId, "prompt_error", new JsonObject()
                                        .put("message", "Draft rendering failed: " + err.getMessage())
                                        .put("promptTitle", prompt.getTitle())
                                        .put("draftTitle", draft.getTitle())
                                        .put("details", errorDetail));
                            })
                            .chain(renderedDraft -> agentClient.testPrompt(prompt.getPrompt(), renderedDraft, context.llmType)
                                    .onFailure().invoke(err -> {
                                        String errorDetail = String.format("LLM call failed. Prompt: '%s', LLM: %s. Error: %s",
                                                prompt.getTitle(), context.llmType, err.getMessage());
                                        LOGGER.error(errorDetail, err);
                                        emit(jobId, "prompt_error", new JsonObject()
                                                .put("message", "LLM call failed: " + err.getMessage())
                                                .put("promptTitle", prompt.getTitle())
                                                .put("llmType", context.llmType.toString()));
                                    })
                                    .map(response -> {
                                        scenarioBuilder.append("```\n").append(response.getResult()).append("\n```\n\n");
                                        return null;
                                    })
                                    .onFailure().recoverWithItem(err -> {
                                        String errorMsg = "Error: " + err.getMessage();
                                        scenarioBuilder.append("Error: ").append(errorMsg).append("\n\n");
                                        return null;
                                    })
                                    .replaceWithVoid());
                });
    }

    private Uni<Void> simulateTimeProgression(StringBuilder scenarioBuilder) {
        int minutesElapsed = ThreadLocalRandom.current().nextInt(5, 20);
        scenarioBuilder.append(minutesElapsed).append(" minutes elapsed\n");
        return Uni.createFrom().voidItem();
    }

    private Uni<Void> maybeInsertSongIntro(UUID brandId, StringBuilder scenarioBuilder) {
        if (ThreadLocalRandom.current().nextBoolean()) {
            return soundFragmentService.getByTypeAndBrand(PlaylistItemType.SONG, brandId)
                    .map(songs -> {
                        if (!songs.isEmpty()) {
                            int randomIndex = ThreadLocalRandom.current().nextInt(songs.size());
                            SoundFragment song = songs.get(randomIndex);
                            scenarioBuilder.append("**Random Song Intro**\n\n");
                            scenarioBuilder.append("- **Title:** ").append(song.getTitle()).append("\n");
                            scenarioBuilder.append("- **Artist:** ").append(song.getArtist() != null ? song.getArtist() : "Unknown").append("\n");
                            scenarioBuilder.append("- **Duration:** ").append(formatDuration(song.getDuration())).append("\n\n");
                        }
                        return null;
                    })
                    .onFailure().recoverWithItem(err -> {
                        LOGGER.warn("Failed to get random song: {}", err.getMessage());
                        return null;
                    })
                    .replaceWithVoid();
        }
        return Uni.createFrom().voidItem();
    }

    private String formatDuration(Integer durationSeconds) {
        if (durationSeconds == null) return "Unknown";
        int minutes = durationSeconds / 60;
        int seconds = durationSeconds % 60;
        return String.format("%d:%02d", minutes, seconds);
    }
}
