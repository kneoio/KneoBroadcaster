package io.kneo.broadcaster.service;

import io.kneo.broadcaster.agent.AgentClient;
import io.kneo.broadcaster.model.Draft;
import io.kneo.broadcaster.model.JobState;
import io.kneo.broadcaster.model.ai.LlmType;
import io.kneo.broadcaster.model.ai.Prompt;
import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.model.radiostation.RadioStation;
import io.kneo.broadcaster.model.soundfragment.SoundFragment;
import io.kneo.broadcaster.service.playlist.SongSupplier;
import io.kneo.core.model.user.IUser;
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
    private final SongSupplier songSupplier;

    public record SseEvent(String type, JsonObject data) {}

    private final Map<String, JobState> jobs = new ConcurrentHashMap<>();
    private final Map<String, List<Consumer<SseEvent>>> subscribers = new ConcurrentHashMap<>();
    private final Map<String, StringBuilder> scenarioBuilders = new ConcurrentHashMap<>();

    @Inject
    public ScriptDryRunService(AgentClient agentClient, ScriptService scriptService, 
                               SceneService sceneService, PromptService promptService,
                               DraftService draftService, RadioStationService radioStationService,
                               SongSupplier songSupplier) {
        this.agentClient = agentClient;
        this.scriptService = scriptService;
        this.sceneService = sceneService;
        this.promptService = promptService;
        this.draftService = draftService;
        this.radioStationService = radioStationService;
        this.songSupplier = songSupplier;
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
                scenarioBuilders.remove(jobId);
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

    public void startDryRun(String jobId, UUID scriptId, String stationName, String djName, IUser user) {
        if (jobId == null || jobId.isBlank()) {
            throw new IllegalArgumentException("jobId is required");
        }

        JobState st = new JobState();
        st.total = 0;
        st.done = 0;
        st.finished = false;
        jobs.put(jobId, st);

        StringBuilder scenarioBuilder = new StringBuilder();
        scenarioBuilders.put(jobId, scenarioBuilder);

        // Add markdown header
        scenarioBuilder.append("# Script Dry-Run Simulation\n\n");
        scenarioBuilder.append("**Station:** ").append(stationName).append("\n\n");
        scenarioBuilder.append("**DJ:** ").append(djName).append("\n\n");
        scenarioBuilder.append("**Started:** ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n\n");
        scenarioBuilder.append("---\n\n");

        emit(jobId, "started", new JsonObject().put("message", "Starting dry-run simulation"));

        // Load script and scenes
        scriptService.getDTO(scriptId, user, io.kneo.core.localization.LanguageCode.en)
                .chain(scriptDTO -> {
                    scenarioBuilder.append("## Script: ").append(scriptDTO.getName()).append("\n\n");
                    scenarioBuilder.append(scriptDTO.getDescription()).append("\n\n");
                    
                    return sceneService.getAllByScript(scriptId, 100, 0, user)
                            .map(scenes -> {
                                st.total = scenes.size();
                                emit(jobId, "total_scenes", new JsonObject().put("total", scenes.size()));
                                return scenes;
                            });
                })
                .chain(scenes -> radioStationService.getBySlugName(stationName)
                        .map(station -> new ProcessContext(scenes, station, djName, user)))
                .chain(context -> processScenes(jobId, context, 0))
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

    private record ProcessContext(List<io.kneo.broadcaster.dto.ScriptSceneDTO> scenes, 
                                   RadioStation station, String djName, IUser user) {}

    private Uni<Void> processScenes(String jobId, ProcessContext context, int idx) {
        if (idx >= context.scenes.size()) {
            return Uni.createFrom().voidItem();
        }

        var sceneDTO = context.scenes.get(idx);
        JobState st = jobs.get(jobId);
        StringBuilder scenarioBuilder = scenarioBuilders.get(jobId);

        emit(jobId, "scene_started", new JsonObject()
                .put("sceneIndex", idx)
                .put("sceneTitle", sceneDTO.getTitle()));

        scenarioBuilder.append("## Scene ").append(idx + 1).append(": ").append(sceneDTO.getTitle()).append("\n\n");
        scenarioBuilder.append("**Start Time:** ").append(sceneDTO.getStartTime() != null ? sceneDTO.getStartTime().toString() : "N/A").append("\n\n");

        // Simulate time progression
        return simulateTimeProgression(jobId, scenarioBuilder)
                .chain(() -> maybeInsertSongIntro(jobId, context.station.getSlugName(), scenarioBuilder))
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

    private Uni<Void> processScenePrompts(String jobId, io.kneo.broadcaster.dto.ScriptSceneDTO sceneDTO, 
                                           ProcessContext context, StringBuilder scenarioBuilder) {
        if (sceneDTO.getPrompts() == null || sceneDTO.getPrompts().isEmpty()) {
            scenarioBuilder.append("*No prompts configured for this scene.*\n\n");
            return Uni.createFrom().voidItem();
        }

        return processPromptSequentially(jobId, sceneDTO.getPrompts(), 0, context, scenarioBuilder);
    }

    private Uni<Void> processPromptSequentially(String jobId, List<UUID> promptIds, int idx, 
                                                 ProcessContext context, StringBuilder scenarioBuilder) {
        if (idx >= promptIds.size()) {
            return Uni.createFrom().voidItem();
        }

        UUID promptId = promptIds.get(idx);
        
        return promptService.getById(promptId, context.user)
                .chain(prompt -> {
                    if (prompt.getDraftId() == null) {
                        scenarioBuilder.append("**Prompt ").append(idx + 1).append(":** ").append(prompt.getTitle()).append(" *(No draft linked)*\n\n");
                        return Uni.createFrom().voidItem();
                    }
                    
                    return draftService.getById(prompt.getDraftId(), context.user)
                            .chain(draft -> testPromptWithDraft(jobId, prompt, draft, context, scenarioBuilder, idx + 1));
                })
                .chain(() -> processPromptSequentially(jobId, promptIds, idx + 1, context, scenarioBuilder));
    }

    private Uni<Void> testPromptWithDraft(String jobId, Prompt prompt, Draft draft, 
                                           ProcessContext context, StringBuilder scenarioBuilder, int promptNumber) {
        emit(jobId, "testing_prompt", new JsonObject()
                .put("promptTitle", prompt.getTitle())
                .put("draftTitle", draft.getTitle()));

        scenarioBuilder.append("### Prompt ").append(promptNumber).append(": ").append(prompt.getTitle()).append("\n\n");
        scenarioBuilder.append("**Draft:** ").append(draft.getTitle()).append("\n\n");

        return agentClient.testPrompt(prompt.getPrompt(), draft.getContent(), LlmType.OPENAI)
                .map(response -> {
                    String result = response != null ? response.getResult() : "No response";
                    scenarioBuilder.append("**AI Response:**\n\n");
                    scenarioBuilder.append("```\n").append(result).append("\n```\n\n");
                    
                    emit(jobId, "prompt_result", new JsonObject()
                            .put("promptTitle", prompt.getTitle())
                            .put("result", result));
                    
                    return null;
                })
                .onFailure().recoverWithItem(err -> {
                    String errorMsg = "Error: " + err.getMessage();
                    scenarioBuilder.append("**Error:** ").append(errorMsg).append("\n\n");
                    emit(jobId, "prompt_error", new JsonObject()
                            .put("promptTitle", prompt.getTitle())
                            .put("error", errorMsg));
                    return null;
                })
                .replaceWithVoid();
    }

    private Uni<Void> simulateTimeProgression(String jobId, StringBuilder scenarioBuilder) {
        int minutesElapsed = ThreadLocalRandom.current().nextInt(5, 20);
        String timeInfo = "⏰ *" + minutesElapsed + " minutes elapsed*\n\n";
        scenarioBuilder.append(timeInfo);
        
        emit(jobId, "time_progress", new JsonObject().put("minutes", minutesElapsed));
        
        return Uni.createFrom().voidItem();
    }

    private Uni<Void> maybeInsertSongIntro(String jobId, String brandName, StringBuilder scenarioBuilder) {
        // 50% chance to insert a random song
        if (ThreadLocalRandom.current().nextBoolean()) {
            return songSupplier.getNextSong(brandName, PlaylistItemType.SONG, 1)
                    .map(songs -> {
                        if (!songs.isEmpty()) {
                            SoundFragment song = songs.get(0);
                            scenarioBuilder.append("🎵 **Random Song Intro**\n\n");
                            scenarioBuilder.append("- **Title:** ").append(song.getTitle()).append("\n");
                            scenarioBuilder.append("- **Artist:** ").append(song.getArtist() != null ? song.getArtist() : "Unknown").append("\n");
                            scenarioBuilder.append("- **Duration:** ").append(formatDuration(song.getDuration())).append("\n\n");
                            
                            emit(jobId, "song_intro", new JsonObject()
                                    .put("title", song.getTitle())
                                    .put("artist", song.getArtist()));
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
