package io.kneo.broadcaster.service;

import io.kneo.broadcaster.dto.aihelper.SongIntroductionDTO;
import io.kneo.broadcaster.dto.memory.MemoryDTO;
import io.kneo.broadcaster.model.cnst.MemoryType;
import io.kneo.broadcaster.model.memory.AudienceContext;
import io.kneo.broadcaster.model.memory.ConversationHistory;
import io.kneo.broadcaster.model.memory.ListenerContext;
import io.kneo.broadcaster.model.memory.Memory;
import io.kneo.broadcaster.repository.MemoryRepository;
import io.kneo.broadcaster.util.TimeContextUtil;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.IUser;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

@ApplicationScoped
public class MemoryService {

    @Inject
    MemoryRepository repository;

    @Inject
    ListenerService listenerService;

    @Inject
    ProfileService profileService;

    @Inject
    RadioStationService radioStationService;

    private final ConcurrentMap<String, List<Memory>> instantMessages = new ConcurrentHashMap<>();

    public Uni<List<MemoryDTO<?>>> getAll(final int limit, final int offset, final IUser user) {
        assert repository != null;
        return repository.getAll(limit, offset, user)
                .map(list -> {
                    List<MemoryDTO<?>> dbMemories = list.stream()
                            .map(this::mapToDTO)
                            .collect(Collectors.toList());
                            
                    List<MemoryDTO<?>> allMemories = new ArrayList<>(dbMemories);
                    
                    instantMessages.forEach((brand, memories) -> {
                        List<MemoryDTO<?>> instantMessageDTOs = memories.stream()
                                .map(this::mapToDTO)
                                .collect(Collectors.toList());
                        allMemories.addAll(instantMessageDTOs);
                    });
                    
                    return allMemories;
                });
    }

    public Uni<Integer> getAllCount(final IUser user) {
        assert repository != null;
        return repository.getAllCount(user, false)
                .map(dbCount -> {
                    int instantMessageCount = instantMessages.values().stream()
                            .mapToInt(List::size)
                            .sum();
                    return dbCount + instantMessageCount;
                });
    }

    public Uni<List<MemoryDTO<?>>> getAll(int limit, int offset) {
        return repository.getAll(limit, offset, null)
                .map(list -> {
                    List<MemoryDTO<?>> dbMemories = list.stream()
                            .map(this::mapToDTO)
                            .collect(Collectors.toList());
                            
                    List<MemoryDTO<?>> allMemories = new ArrayList<>(dbMemories);
                    
                    instantMessages.forEach((brand, memories) -> {
                        List<MemoryDTO<?>> instantMessageDTOs = memories.stream()
                                .map(this::mapToDTO)
                                .collect(Collectors.toList());
                        allMemories.addAll(instantMessageDTOs);
                    });
                    
                    return allMemories.stream()
                            .skip(offset)
                            .limit(limit)
                            .collect(Collectors.toList());
                });
    }

    public Uni<List<MemoryDTO<?>>> getByBrandId(String brand, int limit, int offset) {
        return repository.getByBrandId(brand, limit, offset)
                .map(list -> {
                    List<MemoryDTO<?>> dbMemories = list.stream()
                            .map(this::mapToDTO)
                            .collect(Collectors.toList());
                            
                    List<MemoryDTO<?>> allMemories = new ArrayList<>(dbMemories);
                    
                    List<Memory> brandMessages = instantMessages.getOrDefault(brand, List.of());
                    List<MemoryDTO<?>> instantMessageDTOs = brandMessages.stream()
                            .map(this::mapToDTO)
                            .collect(Collectors.toList());
                    allMemories.addAll(instantMessageDTOs);
                    
                    return allMemories.stream()
                            .skip(offset)
                            .limit(limit)
                            .collect(Collectors.toList());
                });
    }

    public Uni<MemoryDTO<?>> getDTO(UUID id, IUser user, LanguageCode code) {
        return repository.findById(id)
                .onItem().ifNotNull().transform(this::mapToDTO);
    }

    public Uni<List<MemoryDTO<?>>> getByType(String brand, String type, IUser user) {
        MemoryType memoryType = MemoryType.valueOf(type);

        return switch (memoryType) {
            case CONVERSATION_HISTORY -> repository.findByType(brand, memoryType)
                    .map(list -> {
                        if (list.isEmpty()) {
                            List<ConversationHistory> conversations = List.of();
                            Memory entity = new Memory();
                            entity.setBrand(brand);
                            entity.setMemoryType(MemoryType.CONVERSATION_HISTORY);
                            entity.setContent(new JsonObject().put(memoryType.getValue(), new JsonArray(conversations)));
                            return List.of(mapToDTO(entity));
                        }
                        return list.stream()
                                .map(this::mapToDTO)
                                .collect(Collectors.toList());
                    });
            case LISTENER_CONTEXTS -> listenerService.getBrandListenersEntities(brand, 1000, 0, user)
                    .map(brandListeners -> {
                        List<ListenerContext> listeners = brandListeners.stream()
                                .map(bl -> {
                                    ListenerContext context = new ListenerContext();
                                    context.setName(bl.getListener().getLocalizedName().get(LanguageCode.en));
                                    String nickname = bl.getListener().getNickName().get(LanguageCode.en);
                                    if (nickname != null) {
                                        context.setNickname(nickname);
                                    }
                                    context.setLocation(bl.getListener().getCountry().name());
                                    return context;
                                })
                                .collect(Collectors.toList());

                        Memory entity = new Memory();
                        entity.setBrand(brand);
                        entity.setMemoryType(MemoryType.LISTENER_CONTEXTS);
                        entity.setContent(new JsonObject().put(memoryType.getValue(), new JsonArray(listeners)));

                        return List.of(mapToDTO(entity));
                    });
            case AUDIENCE_CONTEXT -> radioStationService.findByBrandName(brand)
                    .chain(radioStation -> profileService.getById(radioStation.getProfileId()))
                    .map(profile -> {
                        AudienceContext audienceContext = new AudienceContext();
                        audienceContext.setName(profile.getName());
                        audienceContext.setDescription(profile.getDescription());
                        audienceContext.setCurrentMoment(TimeContextUtil.getCurrentMomentDetailed());
                        Memory entity = new Memory();
                        entity.setBrand(brand);
                        entity.setMemoryType(MemoryType.AUDIENCE_CONTEXT);
                        entity.setContent(new JsonObject().put(memoryType.getValue(), new JsonArray().add(audienceContext)));
                        return List.of(mapToDTO(entity));
                    });
            case INSTANT_MESSAGE -> {
                List<Memory> messages = instantMessages.getOrDefault(brand, List.of());
                if (messages.isEmpty()) {
                    Memory entity = new Memory();
                    entity.setBrand(brand);
                    entity.setMemoryType(MemoryType.INSTANT_MESSAGE);
                    entity.setContent(new JsonObject().put(memoryType.getValue(), new JsonArray()));
                    yield Uni.createFrom().item(List.of(mapToDTO(entity)));
                }
                yield Uni.createFrom().item(messages.stream()
                    .map(this::mapToDTO)
                    .collect(Collectors.toList()));
            }
            default -> throw new IllegalArgumentException("Unsupported memory type: " + memoryType);
        };
    }

    public Uni<MemoryDTO<?>> upsert(String id, MemoryDTO<?> dto, IUser user) {
        if (dto.getMemoryType() == MemoryType.INSTANT_MESSAGE) {
            return storeInstantMessage(dto.getBrand(), (JsonObject) dto.getContent())
                    .onItem().transform(v -> {
                        Memory memory = new Memory();
                        memory.setBrand(dto.getBrand());
                        memory.setMemoryType(MemoryType.INSTANT_MESSAGE);
                        memory.setContent((JsonObject) dto.getContent());
                        return mapToDTO(memory);
                    });
        } else {
            Memory entity = buildEntity(dto);
            Uni<Memory> operation;
            if (id == null) {
                operation = repository.insert(entity, user);
            } else {
                operation = repository.update(UUID.fromString(id), entity, user);
            }
            return operation.map(this::mapToDTO);
        }
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

    public Uni<Void> storeInstantMessage(String brand, JsonObject message) {
        return Uni.createFrom().voidItem()
                .invoke(() -> {
                    Memory memory = new Memory();
                    memory.setBrand(brand);
                    memory.setMemoryType(MemoryType.INSTANT_MESSAGE);
                    memory.setContent(message);
                    
                    instantMessages.compute(brand, (key, messages) -> {
                        List<Memory> messageList = messages != null ? 
                                new ArrayList<>(messages) : 
                                new ArrayList<>();
                        messageList.add(memory);
                        return messageList;
                    });
                });
    }

    public Uni<List<Memory>> retrieveAndRemoveInstantMessages(String brand) {
        return Uni.createFrom().item(() -> {
            List<Memory> messages = instantMessages.remove(brand);
            return messages != null ? messages : List.of();
        });
    }

    public Uni<List<Memory>> peekInstantMessages(String brand) {
        return Uni.createFrom().item(() -> {
            List<Memory> messages = instantMessages.get(brand);
            return messages != null ? new ArrayList<>(messages) : List.of();
        });
    }

    private Memory buildEntity(MemoryDTO<?> dto) {
        Memory entity = new Memory();
        entity.setBrand(dto.getBrand());
        entity.setMemoryType(dto.getMemoryType());
        entity.setContent((JsonObject) dto.getContent());
        return entity;
    }

    private MemoryDTO<?> mapToDTO(Memory doc) {
        MemoryDTO<Object> dto = new MemoryDTO<>();
        dto.setRegDate(doc.getRegDate());
        dto.setLastModifiedDate(doc.getLastModifiedDate());
        dto.setId(doc.getId());
        dto.setBrand(doc.getBrand());
        dto.setMemoryType(doc.getMemoryType());
        dto.setContent(doc.getContent());
        return dto;
    }
}