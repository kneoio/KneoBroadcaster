package io.kneo.broadcaster.service;

import io.kneo.broadcaster.dto.BrandScriptDTO;
import io.kneo.broadcaster.dto.ScriptDTO;
import io.kneo.broadcaster.model.BrandScript;
import io.kneo.broadcaster.model.Script;
import io.kneo.broadcaster.repository.ScriptRepository;
import io.kneo.broadcaster.repository.ScriptSceneRepository;
import io.kneo.core.dto.DocumentAccessDTO;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.IUser;
import io.kneo.core.service.AbstractService;
import io.kneo.core.service.UserService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class ScriptService extends AbstractService<Script, ScriptDTO> {
    private final ScriptRepository repository;
    private final ScriptSceneService scriptSceneService;
    private final ScriptSceneRepository scriptSceneRepository;

    protected ScriptService() {
        super();
        this.repository = null;
        this.scriptSceneService = null;
        this.scriptSceneRepository = null;
    }

    @Inject
    public ScriptService(UserService userService, ScriptRepository repository, ScriptSceneService scriptSceneService, ScriptSceneRepository scriptSceneRepository) {
        super(userService);
        this.repository = repository;
        this.scriptSceneService = scriptSceneService;
        this.scriptSceneRepository = scriptSceneRepository;
    }

    public Uni<List<ScriptDTO>> getAll(final int limit, final int offset, final IUser user) {
        assert repository != null;
        return repository.getAll(limit, offset, false, user)
                .chain(list -> {
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

    @Override
    public Uni<ScriptDTO> getDTO(UUID id, IUser user, LanguageCode language) {
        assert repository != null;
        return repository.findById(id, user, false).chain(script -> mapToDTO(script, user));
    }

    public Uni<ScriptDTO> upsert(String id, ScriptDTO dto, IUser user) {
        assert repository != null;
        Script entity = buildEntity(dto);
        if (id == null) {
            return repository.insert(entity, user).chain(script -> mapToDTO(script, user));
        } else {
            return repository.update(UUID.fromString(id), entity, user).chain(script -> mapToDTO(script, user));
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
                scriptSceneService.getAll(script.getId(), 100, 0, user)
        ).asTuple().map(tuple -> {
            ScriptDTO dto = new ScriptDTO();
            dto.setId(script.getId());
            dto.setAuthor(tuple.getItem1());
            dto.setRegDate(script.getRegDate());
            dto.setLastModifier(tuple.getItem2());
            dto.setLastModifiedDate(script.getLastModifiedDate());
            dto.setName(script.getName());
            dto.setDescription(script.getDescription());
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
        entity.setLabels(dto.getLabels());
        entity.setBrands(dto.getBrands());
        return entity;
    }

    public Uni<List<BrandScript>> getAllScriptsForBrandWithScenes(UUID brandId, IUser user) {
        assert repository != null;
        return repository.findForBrand(brandId, 100, 0, false, user)
                .chain(list -> {
                    List<Uni<BrandScript>> unis = list.stream()
                            .map(brandScript -> populateScenes(brandScript, user))
                            .collect(Collectors.toList());
                    return Uni.join().all(unis).andFailFast();
                });
    }

    public Uni<List<BrandScriptDTO>> getForBrand(UUID brandId, final int limit, final int offset, IUser user) {
        assert repository != null;
        return repository.findForBrand(brandId, limit, offset, false, user)
                .chain(list -> {
                    List<Uni<BrandScriptDTO>> unis = list.stream()
                            .map(brandScript -> mapBrandScriptToDTO(brandScript, user))
                            .collect(Collectors.toList());
                    return Uni.join().all(unis).andFailFast();
                });
    }

    public Uni<Integer> getForBrandCount(UUID brandId, IUser user) {
        assert repository != null;
        return repository.findForBrandCount(brandId, false, user);
    }

    private Uni<BrandScript> populateScenes(BrandScript brandScript, IUser user) {
        return scriptSceneRepository.listByScript(brandScript.getScript().getId(), 100, 0, false, user)
                .map(scenes -> {
                    brandScript.getScript().setScenes(scenes);
                    return brandScript;
                });
    }

    private Uni<BrandScriptDTO> mapBrandScriptToDTO(BrandScript brandScript, IUser user) {
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
}
