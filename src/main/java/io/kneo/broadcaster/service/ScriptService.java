package io.kneo.broadcaster.service;

import io.kneo.broadcaster.dto.BrandScriptDTO;
import io.kneo.broadcaster.dto.DraftDTO;
import io.kneo.broadcaster.dto.PromptDTO;
import io.kneo.broadcaster.dto.SceneDTO;
import io.kneo.broadcaster.dto.ScenePromptDTO;
import io.kneo.broadcaster.dto.ScriptDTO;
import io.kneo.broadcaster.dto.ScriptExportDTO;
import io.kneo.broadcaster.dto.StagePlaylistDTO;
import io.kneo.broadcaster.dto.filter.ScriptFilterDTO;
import io.kneo.broadcaster.model.Action;
import io.kneo.broadcaster.model.BrandScript;
import io.kneo.broadcaster.model.Draft;
import io.kneo.broadcaster.model.PlaylistRequest;
import io.kneo.broadcaster.model.Prompt;
import io.kneo.broadcaster.model.Scene;
import io.kneo.broadcaster.model.Script;
import io.kneo.broadcaster.model.ScriptFilter;
import io.kneo.broadcaster.model.ScriptVariable;
import io.kneo.broadcaster.model.cnst.LanguageTag;
import io.kneo.broadcaster.model.cnst.SceneTimingMode;
import io.kneo.broadcaster.repository.ScriptRepository;
import io.kneo.broadcaster.util.ScriptVariableExtractor;
import io.kneo.core.dto.DocumentAccessDTO;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.IUser;
import io.kneo.core.model.user.SuperUser;
import io.kneo.core.service.AbstractService;
import io.kneo.core.service.UserService;
import io.kneo.core.util.WebHelper;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
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
        return getAll(limit, offset, user, null);
    }

    public Uni<List<ScriptDTO>> getAll(final int limit, final int offset, final IUser user, final ScriptFilterDTO filterDTO) {
        assert repository != null;
        ScriptFilter filter = toFilter(filterDTO);
        return repository.getAll(limit, offset, false, user, filter)
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

    private ScriptFilter toFilter(ScriptFilterDTO dto) {
        if (dto == null) {
            return null;
        }

        ScriptFilter filter = new ScriptFilter();
        filter.setActivated(dto.isActivated());
        filter.setLabels(dto.getLabels());
        filter.setTimingMode(dto.getTimingMode());
        filter.setLanguageTag(dto.getLanguageTag());
        filter.setSearchTerm(dto.getSearchTerm());

        return filter;
    }

    public Uni<Integer> getAllCount(final IUser user) {
        return getAllCount(user, null);
    }

    public Uni<Integer> getAllCount(final IUser user, final ScriptFilterDTO filterDTO) {
        assert repository != null;
        ScriptFilter filter = toFilter(filterDTO);
        return repository.getAllCount(user, false, filter);
    }

    public Uni<List<ScriptDTO>> getAllShared(final int limit, final int offset, final IUser user) {
        ScriptFilterDTO filterDTO = new ScriptFilterDTO();
        filterDTO.setTimingMode(SceneTimingMode.RELATIVE_TO_STREAM_START);
        return getAllShared(limit, offset, user, filterDTO);
    }

    public Uni<List<ScriptDTO>> getAllShared(final int limit, final int offset, final IUser user, final ScriptFilterDTO filterDTO) {
        assert repository != null;
        ScriptFilter filter = toFilter(filterDTO);
        return repository.getAllShared(limit, offset, user, filter)
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
        return getAllSharedCount(user, null);
    }

    public Uni<Integer> getAllSharedCount(final IUser user, final ScriptFilterDTO filterDTO) {
        assert repository != null;
        ScriptFilter filter = toFilter(filterDTO);
        return repository.getAllSharedCount(user, filter);
    }

    @Override
    public Uni<ScriptDTO> getDTO(UUID id, IUser user, LanguageCode language) {
        assert repository != null;
        return repository.findById(id, user, false).chain(script -> mapToDTO(script, user));
    }

    public Uni<Script> getById(UUID id, IUser user) {
        assert repository != null;
        return repository.findById(id, user, false);
    }

    public Uni<ScriptDTO> upsert(String id, ScriptDTO dto, IUser user) {
        assert repository != null;
        assert scriptSceneService != null;
        
        return extractRequiredVariables(dto.getScenes())
                .chain(requiredVariables -> {
                    Script entity = buildEntity(dto);
                    entity.setRequiredVariables(requiredVariables);
                    
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
                });
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
            dto.setSlugName(script.getSlugName());
            dto.setDefaultProfileId(script.getDefaultProfileId());
            dto.setDescription(script.getDescription());
            dto.setAccessLevel(script.getAccessLevel());
            dto.setLanguageTag(script.getLanguageTag().tag());
            dto.setLabels(script.getLabels());
            dto.setBrands(script.getBrands());
            dto.setTimingMode(script.getTimingMode().name());
            dto.setScenes(tuple.getItem3());
            dto.setRequiredVariables(script.getRequiredVariables());
            return dto;
        });
    }

    private Script buildEntity(ScriptDTO dto) {
        Script entity = new Script();
        entity.setName(dto.getName());
        entity.setSlugName(WebHelper.generateSlug(dto.getName()));
        entity.setDefaultProfileId(dto.getDefaultProfileId());
        entity.setDescription(dto.getDescription());
        entity.setLanguageTag(LanguageTag.fromTag(dto.getLanguageTag()));
        entity.setTimingMode(SceneTimingMode.valueOf(dto.getTimingMode()));
        entity.setLabels(dto.getLabels());
        entity.setBrands(dto.getBrands());
        return entity;
    }

    private Uni<List<ScriptVariable>> extractRequiredVariables(List<SceneDTO> scenes) {
        if (scenes == null || scenes.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }

        List<UUID> draftIds = scenes.stream()
                .filter(scene -> scene.getPrompts() != null)
                .flatMap(scene -> scene.getPrompts().stream())
                .map(ScenePromptDTO::getPromptId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        if (draftIds.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }

        List<Uni<Draft>> draftUnis = draftIds.stream()
                .map(promptId -> {
                    assert promptService != null;
                    return promptService.getById(promptId, SuperUser.build())
                            .chain(prompt -> {
                                if (prompt.getDraftId() == null) {
                                    return Uni.createFrom().nullItem();
                                }
                                assert draftService != null;
                                return draftService.getById(prompt.getDraftId(), SuperUser.build());
                            })
                            .onFailure().recoverWithNull();
                })
                .collect(Collectors.toList());

        return Uni.join().all(draftUnis).andFailFast()
                .map(drafts -> {
                    Map<String, ScriptVariable> aggregated = new LinkedHashMap<>();
                    for (Draft draft : drafts) {
                        if (draft != null && draft.getContent() != null) {
                            List<ScriptVariable> vars = ScriptVariableExtractor.extract(draft.getContent());
                            for (ScriptVariable var : vars) {
                                aggregated.putIfAbsent(var.getName(), var);
                            }
                        }
                    }
                    return new ArrayList<>(aggregated.values());
                });
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
                .map(list -> {
                    brandScript.getScript().setScenes(
                            new TreeSet<>(
                                    Comparator.comparingInt(Scene::getSeqNum)
                                            .thenComparing(Scene::getId)
                            ) {{
                                addAll(list);
                            }}
                    );
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
                                    .map(Action::getPromptId)
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

    private ScriptExportDTO.ScenePromptExportDTO mapPromptToExportDTO(Action action, Map<UUID, Prompt> promptMap, Map<UUID, Draft> draftMap, boolean extended) {
        ScriptExportDTO.ScenePromptExportDTO dto = new ScriptExportDTO.ScenePromptExportDTO();
        dto.setId(action.getPromptId());
        
        Prompt prompt = promptMap.get(action.getPromptId());
        if (prompt != null) {
            dto.setTitle(prompt.getTitle());
            if (extended) {
                dto.setPrompt(prompt.getPrompt());
                dto.setLanguageTag(prompt.getLanguageTag().name());
                
                if (prompt.getDraftId() != null) {
                    Draft draft = draftMap.get(prompt.getDraftId());
                    if (draft != null) {
                        ScriptExportDTO.PromptDraftDTO draftDTO = new ScriptExportDTO.PromptDraftDTO();
                        draftDTO.setId(draft.getId());
                        draftDTO.setContent(draft.getContent());
                        draftDTO.setLanguageTag(draft.getLanguageTag() != null ? draft.getLanguageTag().tag() : null);
                        dto.setDraft(draftDTO);
                    }
                }
            }
        }
        
        dto.setActive(action.isActive());
        dto.setWeight(action.getWeight());
        return dto;
    }

    public Uni<ScriptDTO> importScript(ScriptExportDTO importDTO, IUser user) {
        assert repository != null;
        assert scriptSceneService != null;
        assert promptService != null;
        
        Script script = new Script();
        script.setName(importDTO.getName());
        script.setSlugName(WebHelper.generateSlug(importDTO.getName()));
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
        
        if (exportDTO.getLanguageTag() != null) {
            prompt.setLanguageTag(LanguageTag.valueOf(exportDTO.getLanguageTag()));
        }
        
        if (exportDTO.getDraft() != null) {
            prompt.setDraftId(exportDTO.getDraft().getId());
        }

        assert promptService != null;
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

        assert scriptSceneService != null;
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

    public Uni<List<SceneDTO>> getScenesByScriptId(UUID scriptId, IUser user) {
        assert scriptSceneService != null;
        return scriptSceneService.getAllByScript(scriptId, Integer.MAX_VALUE, 0, user)
                .map(scenes -> scenes.stream()
                        .sorted(Comparator.comparingInt(SceneDTO::getSeqNum))
                        .collect(Collectors.toList()));
    }

    public Uni<List<PromptDTO>> getPromptsBySceneId(UUID sceneId, IUser user) {
        assert scriptSceneService != null;
        assert promptService != null;
        return scriptSceneService.getById(sceneId, user)
                .chain(scene -> {
                    if (scene.getPrompts() == null || scene.getPrompts().isEmpty()) {
                        return Uni.createFrom().item(List.of());
                    }
                    List<UUID> promptIds = scene.getPrompts().stream()
                            .map(Action::getPromptId)
                            .filter(Objects::nonNull)
                            .distinct()
                            .collect(Collectors.toList());
                    if (promptIds.isEmpty()) {
                        return Uni.createFrom().item(List.of());
                    }
                    return promptService.getByIds(promptIds, user)
                            .chain(prompts -> {
                                List<Uni<PromptDTO>> dtoUnis = prompts.stream()
                                        .map(prompt -> promptService.getDTO(prompt.getId(), user, LanguageCode.en))
                                        .collect(Collectors.toList());
                                return Uni.join().all(dtoUnis).andFailFast();
                            });
                });
    }

    public Uni<List<DraftDTO>> getDraftsByPromptId(UUID promptId, IUser user) {
        assert promptService != null;
        assert draftService != null;
        return promptService.getById(promptId, user)
                .chain(prompt -> {
                    if (prompt.getDraftId() == null) {
                        return Uni.createFrom().item(List.<io.kneo.broadcaster.dto.DraftDTO>of());
                    }
                    return draftService.getById(prompt.getDraftId(), user)
                            .chain(draft -> draftService.getDTO(draft.getId(), user, LanguageCode.en)
                                    .map(List::<DraftDTO>of));
                })
                .onFailure().recoverWithItem(List.<io.kneo.broadcaster.dto.DraftDTO>of());
    }

    public Uni<ScriptDTO> cloneScript(UUID scriptId, String newTitle, IUser user) {
        assert repository != null;
        assert scriptSceneService != null;
        assert promptService != null;
        assert draftService != null;

        return repository.findById(scriptId, user, false)
                .chain(originalScript -> {
                    Script clonedScript = new Script();
                    clonedScript.setName(newTitle);
                    clonedScript.setSlugName(WebHelper.generateSlug(newTitle));
                    clonedScript.setDescription(originalScript.getDescription());
                    clonedScript.setDefaultProfileId(originalScript.getDefaultProfileId());
                    clonedScript.setLanguageTag(originalScript.getLanguageTag());
                    clonedScript.setTimingMode(originalScript.getTimingMode());
                    clonedScript.setLabels(originalScript.getLabels());
                    clonedScript.setBrands(originalScript.getBrands());
                    clonedScript.setAccessLevel(originalScript.getAccessLevel());

                    return repository.insert(clonedScript, user)
                            .chain(savedScript -> scriptSceneService.getAllWithPromptIds(scriptId, 1000, 0, user)
                                    .chain(originalScenes -> {
                                        if (originalScenes.isEmpty()) {
                                            return getDTO(savedScript.getId(), user, LanguageCode.en);
                                        }

                                        List<Uni<Scene>> clonedSceneUnis = originalScenes.stream()
                                                .map(originalScene -> cloneScene(savedScript.getId(), originalScene, user))
                                                .collect(Collectors.toList());

                                        return Uni.join().all(clonedSceneUnis).andFailFast()
                                                .chain(() -> getDTO(savedScript.getId(), user, LanguageCode.en));
                                    })
                            );
                });
    }

    private Uni<Scene> cloneScene(UUID newScriptId, Scene originalScene, IUser user) {
        assert scriptSceneService != null;
        assert promptService != null;

        if (originalScene.getPrompts() == null || originalScene.getPrompts().isEmpty()) {
            SceneDTO sceneDTO = buildSceneDTOFromScene(originalScene, newScriptId, null);
            return scriptSceneService.upsert(null, newScriptId, sceneDTO, user)
                    .map(savedDTO -> {
                        Scene scene = new Scene();
                        scene.setId(savedDTO.getId());
                        return scene;
                    });
        }

        List<UUID> promptIds = originalScene.getPrompts().stream()
                .map(Action::getPromptId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        if (promptIds.isEmpty()) {
            SceneDTO sceneDTO = buildSceneDTOFromScene(originalScene, newScriptId, null);
            return scriptSceneService.upsert(null, newScriptId, sceneDTO, user)
                    .map(savedDTO -> {
                        Scene scene = new Scene();
                        scene.setId(savedDTO.getId());
                        return scene;
                    });
        }

        return promptService.getByIds(promptIds, user)
                .chain(originalPrompts -> {
                    List<Uni<Prompt>> clonedPromptUnis = originalPrompts.stream()
                            .map(originalPrompt -> clonePrompt(originalPrompt, user))
                            .collect(Collectors.toList());

                    return Uni.join().all(clonedPromptUnis).andFailFast()
                            .chain(clonedPrompts -> {
                                Map<UUID, UUID> oldToNewPromptIds = new java.util.HashMap<>();
                                for (int i = 0; i < originalPrompts.size(); i++) {
                                    oldToNewPromptIds.put(originalPrompts.get(i).getId(), clonedPrompts.get(i).getId());
                                }

                                SceneDTO sceneDTO = buildSceneDTOFromScene(originalScene, newScriptId, oldToNewPromptIds);
                                return scriptSceneService.upsert(null, newScriptId, sceneDTO, user)
                                        .map(savedDTO -> {
                                            Scene scene = new Scene();
                                            scene.setId(savedDTO.getId());
                                            return scene;
                                        });
                            });
                });
    }

    private Uni<Prompt> clonePrompt(Prompt originalPrompt, IUser user) {
        assert promptService != null;
        assert draftService != null;

        if (originalPrompt.getDraftId() == null) {
            Prompt clonedPrompt = new Prompt();
            clonedPrompt.setTitle(originalPrompt.getTitle());
            clonedPrompt.setPrompt(originalPrompt.getPrompt());
            clonedPrompt.setDescription(originalPrompt.getDescription());
            clonedPrompt.setPromptType(originalPrompt.getPromptType());
            clonedPrompt.setLanguageTag(originalPrompt.getLanguageTag());
            clonedPrompt.setEnabled(originalPrompt.isEnabled());
            clonedPrompt.setMaster(originalPrompt.isMaster());
            clonedPrompt.setLocked(originalPrompt.isLocked());
            clonedPrompt.setBackup(originalPrompt.getBackup());
            clonedPrompt.setPodcast(originalPrompt.isPodcast());
            clonedPrompt.setMasterId(originalPrompt.getMasterId());
            clonedPrompt.setVersion(originalPrompt.getVersion());

            return promptService.insert(clonedPrompt, user);
        }

        return draftService.getById(originalPrompt.getDraftId(), user)
                .chain(originalDraft -> cloneDraft(originalDraft, user))
                .chain(clonedDraft -> {
                    Prompt clonedPrompt = new Prompt();
                    clonedPrompt.setTitle(originalPrompt.getTitle());
                    clonedPrompt.setPrompt(originalPrompt.getPrompt());
                    clonedPrompt.setDescription(originalPrompt.getDescription());
                    clonedPrompt.setPromptType(originalPrompt.getPromptType());
                    clonedPrompt.setLanguageTag(originalPrompt.getLanguageTag());
                    clonedPrompt.setEnabled(originalPrompt.isEnabled());
                    clonedPrompt.setMaster(originalPrompt.isMaster());
                    clonedPrompt.setLocked(originalPrompt.isLocked());
                    clonedPrompt.setBackup(originalPrompt.getBackup());
                    clonedPrompt.setPodcast(originalPrompt.isPodcast());
                    clonedPrompt.setDraftId(clonedDraft.getId());
                    clonedPrompt.setMasterId(originalPrompt.getMasterId());
                    clonedPrompt.setVersion(originalPrompt.getVersion());

                    return promptService.insert(clonedPrompt, user);
                })
                .onFailure().recoverWithUni(failure -> {
                    Prompt clonedPrompt = new Prompt();
                    clonedPrompt.setTitle(originalPrompt.getTitle());
                    clonedPrompt.setPrompt(originalPrompt.getPrompt());
                    clonedPrompt.setDescription(originalPrompt.getDescription());
                    clonedPrompt.setPromptType(originalPrompt.getPromptType());
                    clonedPrompt.setLanguageTag(originalPrompt.getLanguageTag());
                    clonedPrompt.setEnabled(originalPrompt.isEnabled());
                    clonedPrompt.setMaster(originalPrompt.isMaster());
                    clonedPrompt.setLocked(originalPrompt.isLocked());
                    clonedPrompt.setBackup(originalPrompt.getBackup());
                    clonedPrompt.setPodcast(originalPrompt.isPodcast());
                    clonedPrompt.setMasterId(originalPrompt.getMasterId());
                    clonedPrompt.setVersion(originalPrompt.getVersion());

                    return promptService.insert(clonedPrompt, user);
                });
    }

    private Uni<Draft> cloneDraft(Draft originalDraft, IUser user) {
        assert draftService != null;

        DraftDTO clonedDraftDTO = new DraftDTO();
        clonedDraftDTO.setTitle(originalDraft.getTitle());
        clonedDraftDTO.setContent(originalDraft.getContent());
        clonedDraftDTO.setLanguageTag(originalDraft.getLanguageTag().tag());
        clonedDraftDTO.setEnabled(originalDraft.isEnabled());
        clonedDraftDTO.setMaster(originalDraft.isMaster());
        clonedDraftDTO.setLocked(originalDraft.isLocked());
        clonedDraftDTO.setMasterId(originalDraft.getMasterId());
        clonedDraftDTO.setVersion(originalDraft.getVersion());

        return draftService.upsert(null, clonedDraftDTO, user, LanguageCode.en)
                .map(dto -> {
                    Draft draft = new Draft();
                    draft.setId(dto.getId());
                    return draft;
                });
    }

    private SceneDTO buildSceneDTOFromScene(Scene originalScene, UUID newScriptId, Map<UUID, UUID> oldToNewPromptIds) {
        SceneDTO sceneDTO = new SceneDTO();
        sceneDTO.setScriptId(newScriptId);
        sceneDTO.setTitle(originalScene.getTitle());
        sceneDTO.setStartTime(originalScene.getStartTime());
        sceneDTO.setDurationSeconds(originalScene.getDurationSeconds());
        sceneDTO.setSeqNum(originalScene.getSeqNum());
        sceneDTO.setOneTimeRun(originalScene.isOneTimeRun());
        sceneDTO.setWeekdays(originalScene.getWeekdays());
        sceneDTO.setTalkativity(originalScene.getTalkativity());
        sceneDTO.setPodcastMode(originalScene.getPodcastMode());

        if (originalScene.getPrompts() != null && !originalScene.getPrompts().isEmpty() && oldToNewPromptIds != null) {
            List<ScenePromptDTO> promptDTOs = originalScene.getPrompts().stream()
                    .map(action -> {
                        ScenePromptDTO dto = new ScenePromptDTO();
                        UUID newPromptId = oldToNewPromptIds.get(action.getPromptId());
                        dto.setPromptId(newPromptId != null ? newPromptId : action.getPromptId());
                        dto.setRank(action.getRank());
                        dto.setWeight(action.getWeight());
                        dto.setActive(action.isActive());
                        return dto;
                    })
                    .collect(Collectors.toList());
            sceneDTO.setPrompts(promptDTOs);
        }

        if (originalScene.getPlaylistRequest() != null) {
            PlaylistRequest originalPlaylist = originalScene.getPlaylistRequest();
            StagePlaylistDTO playlistDTO = new StagePlaylistDTO();
            playlistDTO.setSourcing(originalPlaylist.getSourcing() != null ? originalPlaylist.getSourcing().name() : null);
            playlistDTO.setTitle(originalPlaylist.getTitle());
            playlistDTO.setArtist(originalPlaylist.getArtist());
            playlistDTO.setGenres(originalPlaylist.getGenres());
            playlistDTO.setLabels(originalPlaylist.getLabels());
            playlistDTO.setType(originalPlaylist.getType() != null ? originalPlaylist.getType().stream().map(Enum::name).toList() : null);
            playlistDTO.setSource(originalPlaylist.getSource() != null ? originalPlaylist.getSource().stream().map(Enum::name).toList() : null);
            playlistDTO.setSearchTerm(originalPlaylist.getSearchTerm());
            playlistDTO.setSoundFragments(originalPlaylist.getSoundFragments());
            sceneDTO.setStagePlaylist(playlistDTO);
        }

        return sceneDTO;
    }
}
