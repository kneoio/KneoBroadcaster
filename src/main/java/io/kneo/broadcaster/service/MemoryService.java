package io.kneo.broadcaster.service;

import io.kneo.broadcaster.dto.ConversationMemoryDTO;
import io.kneo.broadcaster.model.ConversationMemory;
import io.kneo.broadcaster.model.cnst.MemoryType;
import io.kneo.broadcaster.repository.ConversationMemoryRepository;
import io.kneo.core.model.user.IUser;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class MemoryService {

    @Inject
    ConversationMemoryRepository repository;

    public Uni<List<ConversationMemoryDTO>> getAll(int limit, int offset) {
        return repository.getAll(limit, offset, null)
                .map(this::mapEntityListToDtoList);
    }

    public Uni<List<ConversationMemoryDTO>> getByBrandId(String brand, int limit, int offset) {
        return repository.getByBrandId(brand, limit, offset)
                .map(this::mapEntityListToDtoList);
    }

    public Uni<ConversationMemoryDTO> getById(UUID id) {
        return repository.findById(id)
                .onItem().ifNotNull().transform(this::mapEntityToDto);
    }

    public Uni<List<ConversationMemoryDTO>> getByType(String brand, String type) {
        return repository.findByType(brand, MemoryType.valueOf(type))
                .map(this::mapEntityListToDtoList);
    }

    public Uni<ConversationMemoryDTO> upsert(UUID id, ConversationMemoryDTO dto, IUser user) {
        ConversationMemory entity = mapDtoToEntity(dto);
        Uni<ConversationMemory> operation;
        if (id == null) {
            operation = repository.insert(entity, user);
        } else {
            operation = repository.update(id, entity, user);
        }
        return operation.map(this::mapEntityToDto);
    }


    public Uni<Void> delete(UUID id) {
        return repository.delete(id)
                .onItem().transformToUni(count -> Uni.createFrom().voidItem());
    }

    public Uni<Object> deleteByBrand(String brand) {
        return repository.deleteByBrand(brand)
                .onItem().transformToUni(count -> Uni.createFrom().voidItem());
    }

    private List<ConversationMemoryDTO> mapEntityListToDtoList(List<ConversationMemory> entities) {
        return entities.stream()
                .map(this::mapEntityToDto)
                .collect(Collectors.toList());
    }

    private ConversationMemoryDTO mapEntityToDto(ConversationMemory entity) {
        ConversationMemoryDTO dto = new ConversationMemoryDTO();
        dto.setId(entity.getId());
        dto.setBrand(entity.getBrand());
        dto.setMemoryType(entity.getMemoryType());
        dto.setContent(entity.getContent());
        return dto;
    }

    private ConversationMemory mapDtoToEntity(ConversationMemoryDTO dto) {
        ConversationMemory entity = new ConversationMemory();
        entity.setBrand(dto.getBrand());
        entity.setMemoryType(dto.getMemoryType());
        entity.setContent(dto.getContent());
        return entity;
    }


}