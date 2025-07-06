package io.kneo.broadcaster.service;

import io.kneo.broadcaster.dto.aihelper.SongIntroductionDTO;
import io.kneo.broadcaster.dto.memory.MemoryDTO;
import io.kneo.broadcaster.model.cnst.MemoryType;
import io.kneo.broadcaster.model.memory.ListenerContext;
import io.kneo.broadcaster.model.memory.Memory;
import io.kneo.broadcaster.repository.MemoryRepository;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.IUser;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class MemoryService {

    @Inject
    MemoryRepository repository;

    @Inject
    ListenerService listenerService;

    public Uni<List<MemoryDTO<?>>> getAll(final int limit, final int offset, final IUser user) {
        assert repository != null;
        return repository.getAll(limit, offset, user)
                .chain(list -> {
                    if (list.isEmpty()) {
                        return Uni.createFrom().item(List.of());
                    } else {
                        List<Uni<MemoryDTO<?>>> unis = list.stream()
                                .map(this::mapToDTO)
                                .collect(Collectors.toList());
                        return Uni.join().all(unis).andFailFast();
                    }
                });
    }

    public Uni<Integer> getAllCount(final IUser user) {
        assert repository != null;
        return repository.getAllCount(user, false);
    }

    public Uni<List<MemoryDTO<?>>> getAll(int limit, int offset) {
        return repository.getAll(limit, offset, null)
                .map(this::mapEntityListToDtoList);
    }

    public Uni<List<MemoryDTO<?>>> getByBrandId(String brand, int limit, int offset) {
        return repository.getByBrandId(brand, limit, offset)
                .map(this::mapEntityListToDtoList);
    }

    public Uni<MemoryDTO<?>> getDTO(UUID id, IUser user, LanguageCode code) {
        return repository.findById(id)
                .onItem().ifNotNull().transform(this::mapToDto);
    }

    public Uni<List<MemoryDTO<?>>> getByType(String brand, String type, IUser user) {
        MemoryType memoryType = MemoryType.valueOf(type);

        if (memoryType == MemoryType.LISTENER_CONTEXTS) {
            return listenerService.getBrandListenersEntities(brand, 1000, 0, user)
                    .map(brandListeners -> {
                        Map<String, ListenerContext> listeners = brandListeners.stream()
                                .collect(Collectors.toMap(
                                        bl -> bl.getListener().getSlugName(),
                                        bl -> {
                                            ListenerContext context = new ListenerContext();
                                            context.setName(bl.getListener().getLocalizedName().get(LanguageCode.en));
                                            context.setLocation(bl.getListener().getCountry().name());
                                            return context;
                                        }
                                ));

                        Memory entity = new Memory();
                        entity.setBrand(brand);
                        entity.setMemoryType(MemoryType.LISTENER_CONTEXTS);
                        entity.setContent(JsonObject.mapFrom(listeners));

                        return List.of(mapToDto(entity));
                    });
        } else {
            return repository.findByType(brand, memoryType)
                    .map(this::mapEntityListToDtoList);
        }
    }

    public Uni<MemoryDTO<?>> upsert(String id, MemoryDTO<?> dto, IUser user) {
        Memory entity = mapDtoToEntity(dto);
        Uni<Memory> operation;
        if (id == null) {
            operation = repository.insert(entity, user);
        } else {
            operation = repository.update(UUID.fromString(id), entity, user);
        }
        return operation.map(this::mapToDto);
    }

    public Uni<Integer> patch(String brand, SongIntroductionDTO dto, IUser user) {
        return repository.patch(brand, dto.getTitle(), dto.getArtist(), dto.getContent(), user);
    }

    public Uni<Integer> delete(String id) {
        return repository.delete(UUID.fromString(id));
    }

    public Uni<Integer> deleteByBrand(String brand) {
        return repository.deleteByBrand(brand);
    }

    private List<MemoryDTO<?>> mapEntityListToDtoList(List<Memory> entities) {
        return entities.stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    private MemoryDTO<?> mapToDto(Memory entity) {
        MemoryDTO<Object> dto = new MemoryDTO<>();
        dto.setId(entity.getId());
        dto.setRegDate(entity.getRegDate());
        dto.setLastModifiedDate(entity.getLastModifiedDate());
        dto.setBrand(entity.getBrand());
        dto.setMemoryType(entity.getMemoryType());
        dto.setContent(entity.getContent());
        return dto;
    }

    private Memory mapDtoToEntity(MemoryDTO<?> dto) {
        Memory entity = new Memory();
        entity.setBrand(dto.getBrand());
        entity.setMemoryType(dto.getMemoryType());
        entity.setContent((JsonObject) dto.getContent());
        return entity;
    }

    private Uni<MemoryDTO<?>> mapToDTO(Memory doc) {
        return Uni.createFrom().item(() -> {
            MemoryDTO<Object> dto = new MemoryDTO<>();
            dto.setRegDate(doc.getRegDate());
            dto.setLastModifiedDate(doc.getLastModifiedDate());
            dto.setId(doc.getId());
            dto.setBrand(doc.getBrand());
            dto.setMemoryType(doc.getMemoryType());
            dto.setContent(doc.getContent());
            return dto;
        });
    }
}