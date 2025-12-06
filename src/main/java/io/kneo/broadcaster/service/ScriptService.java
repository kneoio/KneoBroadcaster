package io.kneo.broadcaster.service;

import io.kneo.broadcaster.dto.BrandScriptDTO;
import io.kneo.broadcaster.dto.SceneDTO;
import io.kneo.broadcaster.dto.ScenePromptDTO;
import io.kneo.broadcaster.dto.ScriptDTO;
import io.kneo.broadcaster.dto.ScriptExportDTO;
import io.kneo.broadcaster.model.BrandScript;
import io.kneo.broadcaster.model.Draft;
import io.kneo.broadcaster.model.Prompt;
import io.kneo.broadcaster.model.Scene;
import io.kneo.broadcaster.model.ScenePrompt;
import io.kneo.broadcaster.model.Script;
import io.kneo.broadcaster.repository.ScriptRepository;
import io.kneo.core.dto.DocumentAccessDTO;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.IUser;
import io.kneo.core.model.user.SuperUser;
import io.kneo.core.service.AbstractService;
import io.kneo.core.service.UserService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class ScriptService extends AbstractService<Script, ScriptDTO> {
    private final ScriptRepository repository;
    private final SceneService scriptSceneService;
    private final PromptService promptService;
    private final DraftService draftService;

    protected ScriptService() {
        super();
        this.repository = null;
        this.scriptSceneService = null;
        this.promptService = null;
        this.draftService = null;
    }

    @Inject
    public ScriptService(UserService userService, ScriptRepository repository, SceneService scriptSceneService, PromptService promptService, DraftService draftService) {
        super(userService);
        this.repository = repository;
        this.scriptSceneService = scriptSceneService;
        this.promptService = promptService;
        this.draftService = draftService;
    }

    public Uni<List<ScriptDTO>> getAll(final int limit, final int offset, final IUser user) {
        assert repository != null;
        return repository.getAll(limit, offset, false, user)
                .chain(list -> {
                    if (list.isEmpty()) {
                        return Uni.createFrom().item(List.of());
                    }
                    List<Uni<ScriptDTO>> unis = list.stream()
                            .map(script -> mapToDTO(script, user))
                            .collect(Collectors.toList());
                    return Uni.join().all(unis).andFailFast();
                });
    }

    public Uni<Integer> getAllCount(final IUser user) {
        assert repository != null;
        return repository.getAllCount(user, false);
    }

    public Uni<List<ScriptDTO>> getAllShared(final int limit, final int offset, final IUser user) {
        assert repository != null;
        return repository.getAllShared(limit, offset, user)
                .chain(list -> {
                    if (list.isEmpty()) {
                        return Uni.createFrom().item(List.of());
                    }
                    List<Uni<ScriptDTO>> unis = list.stream()
                            .map(script -> mapToDTO(script, user))
                            .collect(Collectors.toList());
                    return Uni.join().all(unis).andFailFast();
                });
    }

    public Uni<Integer> getAllSharedCount(final IUser user) {
        assert repository != null;
        return repository.getAllSharedCount(user);
    }

    @Override
    public Uni<ScriptDTO> getDTO(UUID id, IUser user, LanguageCode language) {
        assert repository != null;
        return repository.findById(id, user, false).chain(script -> mapToDTO(script, user));
    }

    public Uni<ScriptDTO> upsert(String id, ScriptDTO dto, IUser user) {
        assert repository != null;
        assert scriptSceneService != null;
        Script entity = buildEntity(dto);
        
        if (id == null) {
            return repository.insert(entity, user)
                    .chain(script -> processScenes(script.getId(), dto.getScenes(), user)
                            .replaceWith(script))
                    .chain(script -> mapToDTO(script, user));
        } else {
            UUID scriptId = UUID.fromString(id);
            return repository.update(scriptId, entity, user)
                    .chain(script -> processScenes(scriptId, dto.getScenes(), user)
                            .replaceWith(script))
                    .chain(script -> mapToDTO(script, user));
        }
    }

    public Uni<Integer> archive(String id, IUser user) {
        assert repository != null;
        return repository.archive(UUID.fromString(id), user);
    }

    @Override
    public Uni<Integer> delete(String id, IUser user) {
        assert repository != null;
        return repository.delete(UUID.fromString(id), user);
    }

    private Uni<ScriptDTO> mapToDTO(Script script, IUser user) {
        assert scriptSceneService != null;
        return Uni.combine().all().unis(
                userService.getUserName(script.getAuthor()),
                userService.getUserName(script.getLastModifier()),
                scriptSceneService.getAllByScript(script.getId(), 100, 0, user)
        ).asTuple().map(tuple -> {
            ScriptDTO dto = new ScriptDTO();
            dto.setId(script.getId());
            dto.setAuthor(tuple.getItem1());
            dto.setRegDate(script.getRegDate());
            dto.setLastModifier(tuple.getItem2());
            dto.setLastModifiedDate(script.getLastModifiedDate());
            dto.setName(script.getName());
            dto.setDescription(script.getDescription());
            dto.setAccessLevel(script.getAccessLevel());
            dto.setLanguageCode(script.getLanguageCode());
            dto.setLabels(script.getLabels());
            dto.setBrands(script.getBrands());
            dto.setScenes(tuple.getItem3());
            return dto;
        });
    }

    private Script buildEntity(ScriptDTO dto) {
        Script entity = new Script();
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setLanguageCode(dto.getLanguageCode());
        entity.setLabels(dto.getLabels());
        entity.setBrands(dto.getBrands());
        return entity;
    }

    public Uni<ScriptDTO> updateAccessLevel(String id, Integer accessLevel, IUser user) {
        assert repository != null;
        UUID uuid = UUID.fromString(id);
        return repository.updateAccessLevel(uuid, accessLevel, user)
                .chain(script -> mapToDTO(script, user));
    }

    public Uni<List<BrandScript>> getAllScriptsForBrandWithScenes(UUID brandId, IUser user) {
        assert repository != null;
        return repository.findForBrand(brandId, 100, 0, false, user)
                .chain(list -> {
                    if (list.isEmpty()) {
                        return Uni.createFrom().item(List.of());
                    }
                    List<Uni<BrandScript>> unis = list.stream()
                            .map(brandScript -> populateScenesWithPrompts(brandScript, user))
                            .collect(Collectors.toList());
                    return Uni.join().all(unis).andFailFast();
                });
    }

    public Uni<List<BrandScriptDTO>> getForBrand(UUID brandId, final int limit, final int offset, IUser user) {
        assert repository != null;
        return repository.findForBrand(brandId, limit, offset, false, user)
                .chain(list -> {
                    if (list.isEmpty()) {
                        return Uni.createFrom().item(List.of());
                    }
                    List<Uni<BrandScriptDTO>> unis = list.stream()
                            .map(brandScript -> mapToDTO(brandScript, user))
                            .collect(Collectors.toList());
                    return Uni.join().all(unis).andFailFast();
                });
    }

    public Uni<Integer> getForBrandCount(UUID brandId, IUser user) {
        assert repository != null;
        return repository.findForBrandCount(brandId, false, user);
    }

    private Uni<BrandScript> populateScenesWithPrompts(BrandScript brandScript, IUser user) {
        assert scriptSceneService != null;
        return scriptSceneService.getAllWithPromptIds(brandScript.getScript().getId(), 100, 0, user)
                .map(scenes -> {
                    brandScript.getScript().setScenes(scenes);
                    return brandScript;
                });
    }

    private Uni<BrandScriptDTO> mapToDTO(BrandScript brandScript, IUser user) {
        return mapToDTO(brandScript.getScript(), user).map(scriptDTO -> {
            BrandScriptDTO dto = new BrandScriptDTO();
            dto.setId(brandScript.getId());
            dto.setDefaultBrandId(brandScript.getDefaultBrandId());
            dto.setRank(brandScript.getRank());
            dto.setActive(brandScript.isActive());
            dto.setScript(scriptDTO);
            dto.setRepresentedInBrands(brandScript.getRepresentedInBrands());
            return dto;
        });
    }


    public Uni<List<DocumentAccessDTO>> getDocumentAccess(UUID documentId, IUser user) {
        assert repository != null;
        return repository.getDocumentAccessInfo(documentId, user)
                .onItem().transform(accessInfoList ->
                        accessInfoList.stream()
                                .map(this::mapToDocumentAccessDTO)
                                .collect(Collectors.toList())
                );
    }

    private Uni<Void> processScenes(UUID scriptId, List<SceneDTO> sceneDTOs, IUser user) {
        assert scriptSceneService != null;
        return scriptSceneService.getAllByScript(scriptId, 1000, 0, user)
                .chain(existingScenes -> {
                    List<UUID> incomingSceneIds = sceneDTOs != null ? sceneDTOs.stream()
                            .map(SceneDTO::getId)
                            .filter(Objects::nonNull)
                            .toList() : List.of();

                    List<UUID> scenesToDelete = existingScenes.stream()
                            .map(SceneDTO::getId)
                            .filter(id -> !incomingSceneIds.contains(id))
                            .toList();

                    Uni<Void> deleteUni = scenesToDelete.isEmpty()
                            ? Uni.createFrom().voidItem()
                            : Uni.join().all(scenesToDelete.stream()
                                    .map(id -> scriptSceneService.delete(id.toString(), user))
                                    .collect(Collectors.toList()))
                                    .andFailFast()
                                    .replaceWithVoid();

                    if (sceneDTOs == null || sceneDTOs.isEmpty()) {
                        return deleteUni;
                    }

                    List<Uni<SceneDTO>> upsertUnis = sceneDTOs.stream()
                            .map(sceneDTO -> {
                                String sceneId = sceneDTO.getId() != null ? sceneDTO.getId().toString() : null;
                                return scriptSceneService.upsert(sceneId, scriptId, sceneDTO, user);
                            })
                            .collect(Collectors.toList());

                    return deleteUni.chain(() -> Uni.join().all(upsertUnis).andFailFast().replaceWithVoid());
                });
    }

    public Uni<List<BrandScriptDTO>> getBrandScripts(String brandName, final int limit, final int offset, IUser user) {
        assert repository != null;
        return repository.findForBrandByName(brandName, limit, offset, user)
                .chain(list -> {
                    if (list.isEmpty()) {
                        return Uni.createFrom().item(List.of());
                    }
                    List<Uni<BrandScriptDTO>> unis = list.stream()
                            .map(brandScript -> mapToDTO(brandScript, user))
                            .collect(Collectors.toList());
                    return Uni.join().all(unis).andFailFast();
                });
    }

    public Uni<Integer> getCountBrandScripts(String brandName, IUser user) {
        assert repository != null;
        return repository.findForBrandByNameCount(brandName, user);
    }

    public Uni<ScriptExportDTO> exportScript(UUID scriptId, IUser user, boolean extended) {
        assert repository != null;
        assert scriptSceneService != null;
        assert promptService != null;
        assert draftService != null;
        
        return repository.findById(scriptId, user, false)
                .chain(script -> scriptSceneService.getAllWithPromptIds(scriptId, 1000, 0, user)
                        .chain(scenes -> {
                            List<UUID> promptIds = scenes.stream()
                                    .flatMap(scene -> scene.getPrompts() != null ? scene.getPrompts().stream() : java.util.stream.Stream.empty())
                                    .map(ScenePrompt::getPromptId)
                                    .distinct()
                                    .collect(Collectors.toList());
                            
                            if (promptIds.isEmpty()) {
                                return Uni.createFrom().item(mapToExportDTO(script, scenes, Map.of(), Map.of(), extended));
                            }
                            
                            return promptService.getByIds(promptIds, user)
                                    .chain(prompts -> {
                                        Map<UUID, Prompt> promptMap = prompts.stream()
                                                .collect(Collectors.toMap(Prompt::getId, p -> p));
                                        
                                        if (!extended) {
                                            return Uni.createFrom().item(mapToExportDTO(script, scenes, promptMap, Map.of(), extended));
                                        }
                                        
                                        List<UUID> draftIds = prompts.stream()
                                                .map(Prompt::getDraftId)
                                                .filter(java.util.Objects::nonNull)
                                                .distinct()
                                                .collect(Collectors.toList());
                                        
                                        if (draftIds.isEmpty()) {
                                            return Uni.createFrom().item(mapToExportDTO(script, scenes, promptMap, Map.of(), extended));
                                        }
                                        
                                        return draftService.getByIds(draftIds, user)
                                                .map(drafts -> {
                                                    Map<UUID, Draft> draftMap = drafts.stream()
                                                            .collect(Collectors.toMap(Draft::getId, d -> d));
                                                    return mapToExportDTO(script, scenes, promptMap, draftMap, extended);
                                                });
                                    });
                        })
                );
    }

    private ScriptExportDTO mapToExportDTO(Script script, List<Scene> scenes, Map<UUID, Prompt> promptMap, Map<UUID, Draft> draftMap, boolean extended) {
        ScriptExportDTO dto = new ScriptExportDTO();
        dto.setName(script.getName());
        dto.setDescription(script.getDescription());
        dto.setLabels(script.getLabels());
        dto.setExtended(extended);
        
        if (scenes != null && !scenes.isEmpty()) {
            List<ScriptExportDTO.SceneExportDTO> sceneDTOs = scenes.stream()
                    .map(scene -> mapSceneToExportDTO(scene, promptMap, draftMap, extended))
                    .collect(Collectors.toList());
            dto.setScenes(sceneDTOs);
        }
        
        return dto;
    }

    private ScriptExportDTO.SceneExportDTO mapSceneToExportDTO(Scene scene, Map<UUID, Prompt> promptMap, Map<UUID, Draft> draftMap, boolean extended) {
        ScriptExportDTO.SceneExportDTO dto = new ScriptExportDTO.SceneExportDTO();
        dto.setTitle(scene.getTitle());
        dto.setStartTime(scene.getStartTime());
        dto.setOneTimeRun(scene.isOneTimeRun());
        dto.setTalkativity(scene.getTalkativity());
        dto.setPodcastMode(scene.getPodcastMode());
        dto.setWeekdays(scene.getWeekdays());
        
        if (scene.getPrompts() != null && !scene.getPrompts().isEmpty()) {
            List<ScriptExportDTO.ScenePromptExportDTO> promptDTOs = scene.getPrompts().stream()
                    .map(prompt -> mapPromptToExportDTO(prompt, promptMap, draftMap, extended))
                    .collect(Collectors.toList());
            dto.setActions(promptDTOs);
        }
        
        return dto;
    }

    private ScriptExportDTO.ScenePromptExportDTO mapPromptToExportDTO(ScenePrompt scenePrompt, Map<UUID, Prompt> promptMap, Map<UUID, Draft> draftMap, boolean extended) {
        ScriptExportDTO.ScenePromptExportDTO dto = new ScriptExportDTO.ScenePromptExportDTO();
        dto.setId(scenePrompt.getPromptId());
        
        Prompt prompt = promptMap.get(scenePrompt.getPromptId());
        if (prompt != null) {
            dto.setTitle(prompt.getTitle());
            if (extended) {
                dto.setPrompt(prompt.getPrompt());
                dto.setLanguageCode(prompt.getLanguageCode().name());
                
                if (prompt.getDraftId() != null) {
                    Draft draft = draftMap.get(prompt.getDraftId());
                    if (draft != null) {
                        ScriptExportDTO.PromptDraftDTO draftDTO = new ScriptExportDTO.PromptDraftDTO();
                        draftDTO.setId(draft.getId());
                        draftDTO.setContent(draft.getContent());
                        draftDTO.setLanguageCode(draft.getLanguageCode() != null ? draft.getLanguageCode().name() : null);
                        dto.setDraft(draftDTO);
                    }
                }
            }
        }
        
        dto.setActive(scenePrompt.isActive());
        dto.setWeight(scenePrompt.getWeight());
        return dto;
    }

    public Uni<ScriptDTO> importScript(ScriptExportDTO importDTO, IUser user) {
        assert repository != null;
        assert scriptSceneService != null;
        assert promptService != null;
        
        Script script = new Script();
        script.setName(importDTO.getName());
        script.setDescription(importDTO.getDescription());
        script.setAccessLevel(0);
        script.setLabels(importDTO.getLabels());
        
        return repository.insert(script, user)
                .chain(savedScript -> {
                    if (importDTO.getScenes() == null || importDTO.getScenes().isEmpty()) {
                        return getDTO(savedScript.getId(), user, LanguageCode.en);
                    }
                    
                    Uni<Void> importOperation;
                    if (importDTO.isExtended()) {
                        importOperation = importScenesWithPrompts(savedScript.getId(), importDTO.getScenes(), user);
                    } else {
                        List<Uni<Scene>> sceneUnis = importDTO.getScenes().stream()
                                .map(sceneDTO -> importScene(savedScript.getId(), sceneDTO, user, null))
                                .collect(Collectors.toList());
                        importOperation = Uni.join().all(sceneUnis).andFailFast().replaceWithVoid();
                    }
                    
                    return importOperation
                            .chain(() -> getDTO(savedScript.getId(), user, LanguageCode.en))
                            .onFailure().recoverWithUni(failure -> 
                                    repository.delete(savedScript.getId(), SuperUser.build(), true)
                                            .onFailure().invoke(deleteError -> 
                                                    System.err.println("Failed to cleanup script after import failure: " + deleteError.getMessage())
                                            )
                                            .onFailure().recoverWithNull()
                                            .chain(() -> Uni.createFrom().failure(failure))
                            );
                });
    }

    private Uni<Void> importScenesWithPrompts(UUID scriptId, List<ScriptExportDTO.SceneExportDTO> sceneDTOs, IUser user) {
        List<Uni<Void>> sceneUnis = sceneDTOs.stream()
                .map(sceneDTO -> {
                    if (sceneDTO.getActions() == null || sceneDTO.getActions().isEmpty()) {
                        return importScene(scriptId, sceneDTO, user, null).replaceWithVoid();
                    }
                    
                    Map<String, ScriptExportDTO.ScenePromptExportDTO> uniquePrompts = sceneDTO.getActions().stream()
                            .filter(action -> action.getPrompt() != null)
                            .collect(Collectors.toMap(
                                    action -> action.getPrompt() + "|" + (action.getTitle() != null ? action.getTitle() : ""),
                                    action -> action,
                                    (existing, replacement) -> existing
                            ));
                    
                    if (uniquePrompts.isEmpty()) {
                        return importScene(scriptId, sceneDTO, user, null).replaceWithVoid();
                    }
                    
                    List<Uni<Prompt>> promptUnis = uniquePrompts.values().stream()
                            .map(action -> createPromptFromExport(action, user))
                            .collect(Collectors.toList());
                    
                    List<String> promptKeys = new ArrayList<>(uniquePrompts.keySet());
                    
                    return Uni.join().all(promptUnis).andFailFast()
                            .chain(createdPrompts -> {
                                Map<String, UUID> promptKeyToNewId = new java.util.HashMap<>();
                                for (int i = 0; i < createdPrompts.size(); i++) {
                                    promptKeyToNewId.put(promptKeys.get(i), createdPrompts.get(i).getId());
                                }
                                
                                Map<ScriptExportDTO.ScenePromptExportDTO, UUID> actionToPromptId = new java.util.HashMap<>();
                                for (ScriptExportDTO.ScenePromptExportDTO action : sceneDTO.getActions()) {
                                    if (action.getPrompt() != null) {
                                        String key = action.getPrompt() + "|" + (action.getTitle() != null ? action.getTitle() : "");
                                        actionToPromptId.put(action, promptKeyToNewId.get(key));
                                    }
                                }
                                
                                return importScene(scriptId, sceneDTO, user, actionToPromptId).replaceWithVoid();
                            });
                })
                .collect(Collectors.toList());
        
        return Uni.join().all(sceneUnis).andFailFast().replaceWithVoid();
    }

    private Uni<Prompt> createPromptFromExport(ScriptExportDTO.ScenePromptExportDTO exportDTO, IUser user) {
        Prompt prompt = new Prompt();
        prompt.setTitle(exportDTO.getTitle() + " (imported)");
        prompt.setPrompt(exportDTO.getPrompt());
        
        if (exportDTO.getLanguageCode() != null) {
            prompt.setLanguageCode(LanguageCode.valueOf(exportDTO.getLanguageCode()));
        }
        
        if (exportDTO.getDraft() != null) {
            prompt.setDraftId(exportDTO.getDraft().getId());
        }
        
        return promptService.insert(prompt, user);
    }

    private Uni<Scene> importScene(UUID scriptId, ScriptExportDTO.SceneExportDTO sceneDTO, IUser user, Map<ScriptExportDTO.ScenePromptExportDTO, UUID> actionToPromptId) {
        SceneDTO dto = new SceneDTO();
        dto.setScriptId(scriptId);
        dto.setTitle(sceneDTO.getTitle() + " (imported)");
        dto.setStartTime(sceneDTO.getStartTime());
        dto.setOneTimeRun(sceneDTO.isOneTimeRun());
        dto.setTalkativity(sceneDTO.getTalkativity());
        dto.setPodcastMode(sceneDTO.getPodcastMode());
        dto.setWeekdays(sceneDTO.getWeekdays());
        
        if (sceneDTO.getActions() != null && !sceneDTO.getActions().isEmpty()) {
            List<ScenePromptDTO> promptDTOs = sceneDTO.getActions().stream()
                    .map(action -> importScenePromptDTO(action, actionToPromptId))
                    .collect(Collectors.toList());
            dto.setPrompts(promptDTOs);
        }
        
        return scriptSceneService.upsert(null, scriptId, dto, user)
                .map(savedDTO -> {
                    Scene scene = new Scene();
                    scene.setId(savedDTO.getId());
                    return scene;
                });
    }

    private ScenePromptDTO importScenePromptDTO(ScriptExportDTO.ScenePromptExportDTO promptDTO, Map<ScriptExportDTO.ScenePromptExportDTO, UUID> actionToPromptId) {
        ScenePromptDTO dto = new ScenePromptDTO();
        UUID promptId = actionToPromptId != null ? actionToPromptId.get(promptDTO) : null;
        dto.setPromptId(promptId);
        dto.setActive(promptDTO.isActive());
        dto.setRank(0);
        dto.setWeight(promptDTO.getWeight() != null ? promptDTO.getWeight() : java.math.BigDecimal.valueOf(0.5));
        return dto;
    }
}
