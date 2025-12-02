package io.kneo.broadcaster.service;

import io.kneo.broadcaster.dto.BrandScriptDTO;
import io.kneo.broadcaster.dto.SceneDTO;
import io.kneo.broadcaster.dto.ScriptDTO;
import io.kneo.broadcaster.model.BrandScript;
import io.kneo.broadcaster.model.Script;
import io.kneo.broadcaster.repository.ScriptRepository;
import io.kneo.core.dto.DocumentAccessDTO;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.IUser;
import io.kneo.core.service.AbstractService;
import io.kneo.core.service.UserService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class ScriptService extends AbstractService<Script, ScriptDTO> {
    private final ScriptRepository repository;
    private final SceneService scriptSceneService;

    protected ScriptService() {
        super();
        this.repository = null;
        this.scriptSceneService = null;
    }

    @Inject
    public ScriptService(UserService userService, ScriptRepository repository, SceneService scriptSceneService) {
        super(userService);
        this.repository = repository;
        this.scriptSceneService = scriptSceneService;
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
        entity.setAccessLevel(dto.getAccessLevel());
        entity.setLabels(dto.getLabels());
        entity.setBrands(dto.getBrands());
        return entity;
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
}
