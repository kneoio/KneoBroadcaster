package io.kneo.broadcaster.service;

import io.kneo.broadcaster.dto.aihelper.SongIntroductionDTO;
import io.kneo.broadcaster.dto.memory.AudienceContextDTO;
import io.kneo.broadcaster.dto.memory.ConversationHistoryDTO;
import io.kneo.broadcaster.dto.memory.EventDTO;
import io.kneo.broadcaster.dto.memory.IMemoryContentDTO;
import io.kneo.broadcaster.dto.memory.ListenerContext;
import io.kneo.broadcaster.dto.memory.MemoryDTO;
import io.kneo.broadcaster.dto.memory.MessageDTO;
import io.kneo.broadcaster.model.cnst.MemoryType;
import io.kneo.broadcaster.util.TimeContextUtil;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.IUser;
import io.kneo.core.model.user.SuperUser;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

@ApplicationScoped
public class MemoryService {

    private static final int MAX_CONVERSATION_HISTORY = 1;
    private static final int MAX_EVENTS = 20;
    private static final int MAX_MESSAGES = 20;

    @Inject
    ListenerService listenerService;

    @Inject
    ProfileService profileService;

    @Inject
    RadioStationService radioStationService;

    private final ConcurrentMap<String, ConcurrentMap<String, MemoryDTO>> memories = new ConcurrentHashMap<>();

    public Uni<List<MemoryDTO>> getAll(final int limit, final int offset, final IUser user) {
        return flattenMemories().map(allMemories ->
                allMemories.stream()
                        .skip(offset)
                        .limit(limit > 0 ? limit : allMemories.size())
                        .collect(Collectors.toList())
        );
    }

    public Uni<Integer> getAllCount(IUser user) {
        return flattenMemories().map(List::size);
    }

    public Uni<MemoryDTO> getDTO(UUID id, IUser user, LanguageCode code) {
        return flattenMemories().map(allMemories ->
                allMemories.stream()
                        .filter(memory -> id.equals(memory.getId()))
                        .findFirst()
                        .orElse(null)
        );
    }

    public Uni<JsonObject> getByType(String brand, String... types) {
        JsonObject result = new JsonObject();
        List<Uni<Void>> uniList = new ArrayList<>();

        for (String type : types) {
            MemoryType memoryType = MemoryType.valueOf(type);

            switch (memoryType) {
                case CONVERSATION_HISTORY -> {
                    ConcurrentMap<String, MemoryDTO> brandMap = memories.get(brand);
                    if (brandMap != null) {
                        JsonArray historyArray = new JsonArray();
                        brandMap.values().stream()
                                .filter(dto -> dto.getMemoryType() == MemoryType.CONVERSATION_HISTORY)
                                .forEach(dto -> {
                                    List<IMemoryContentDTO> content = dto.getContent();
                                    if (content != null && !content.isEmpty()) {
                                        content.forEach(historyItem -> {
                                            JsonObject historyWithId = new JsonObject()
                                                    .put("id", dto.getId().toString())
                                                    .put("content", historyItem);
                                            historyArray.add(historyWithId);
                                        });
                                    }
                                });
                        result.put(MemoryType.CONVERSATION_HISTORY.getValue(), historyArray);
                    }
                    if (!result.containsKey(MemoryType.CONVERSATION_HISTORY.getValue())) {
                        result.put(MemoryType.CONVERSATION_HISTORY.getValue(), new JsonArray());
                    }
                }
                case LISTENER_CONTEXT -> {
                    Uni<Void> listenerUni = listenerService.getBrandListenersEntities(brand, 1000, 0, SuperUser.build())
                            .map(brandListeners -> {
                                List<ListenerContext> listeners = brandListeners.stream()
                                        .map(bl -> {
                                            ListenerContext context = new ListenerContext();
                                            context.setName(bl.getListener().getLocalizedName().get(LanguageCode.en));
                                            String nickname = bl.getListener().getNickName().get(LanguageCode.en);
                                            if (nickname != null) {
                                                context.setNickname(nickname);
                                            }
                                            context.setLocation(bl.getListener().getCountry().getCountryName());
                                            return context;
                                        })
                                        .collect(Collectors.toList());

                                result.put(MemoryType.LISTENER_CONTEXT.getValue(), new JsonArray(listeners));
                                return null;
                            });
                    uniList.add(listenerUni);
                }
                case AUDIENCE_CONTEXT -> {
                    Uni<Void> audienceUni = radioStationService.getBySlugName(brand)
                            .chain(radioStation -> profileService.getById(radioStation.getProfileId()))
                            .map(profile -> {
                                AudienceContextDTO audienceContext = new AudienceContextDTO();
                                audienceContext.setName(profile.getName());
                                audienceContext.setDescription(profile.getDescription());
                                audienceContext.setCurrentMoment(TimeContextUtil.getCurrentMomentDetailed());

                                result.put(MemoryType.AUDIENCE_CONTEXT.getValue(), new JsonArray().add(audienceContext));
                                return null;
                            });
                    uniList.add(audienceUni);
                }
                case MESSAGE -> {
                    ConcurrentMap<String, MemoryDTO> brandMap = memories.get(brand);
                    if (brandMap != null) {
                        JsonArray messageArray = new JsonArray();
                        brandMap.values().stream()
                                .filter(dto -> dto.getMemoryType() == MemoryType.MESSAGE)
                                .forEach(dto -> {
                                    List<IMemoryContentDTO> content = dto.getContent();
                                    if (content != null && !content.isEmpty()) {
                                        content.forEach(message -> {
                                            JsonObject messageWithId = new JsonObject()
                                                    .put("id", dto.getId().toString())
                                                    .put("content", message);
                                            messageArray.add(messageWithId);
                                        });
                                    }
                                });
                        result.put(MemoryType.MESSAGE.getValue(), messageArray);
                    }
                    if (!result.containsKey(MemoryType.MESSAGE.getValue())) {
                        result.put(MemoryType.MESSAGE.getValue(), new JsonArray());
                    }
                }
                case EVENT -> {
                    ConcurrentMap<String, MemoryDTO> brandMap = memories.get(brand);
                    if (brandMap != null) {
                        JsonArray eventArray = new JsonArray();
                        brandMap.values().stream()
                                .filter(dto -> dto.getMemoryType() == MemoryType.EVENT)
                                .findFirst()
                                .ifPresent(dto -> {
                                    List<IMemoryContentDTO> content = dto.getContent();
                                    if (content != null && !content.isEmpty()) {
                                        JsonObject eventWithId = new JsonObject()
                                                .put("id", dto.getId().toString())
                                                .put("content", content.get(0));
                                        eventArray.add(eventWithId);
                                    }
                                });
                        result.put(MemoryType.EVENT.getValue(), eventArray);
                    }
                    if (!result.containsKey(MemoryType.EVENT.getValue())) {
                        result.put(MemoryType.EVENT.getValue(), new JsonArray());
                    }
                }
            }
        }

        if (uniList.isEmpty()) {
            return Uni.createFrom().item(result);
        }

        return Uni.combine().all().unis(uniList).with(results -> result);
    }

    public Uni<String> addEvent(String brand, String timestamp, String description) {
        if (description == null || description.isBlank()) {
            return Uni.createFrom().failure(new IllegalArgumentException("Event description cannot be empty"));
        }
        EventDTO eventDTO = new EventDTO();
        eventDTO.setTimestamp(timestamp);
        eventDTO.setDescription(description);
        return add(brand, MemoryType.EVENT, eventDTO);
    }


    public Uni<String> addMessage(String brand, String from, String message) {
        MessageDTO messageDTO = new MessageDTO();
        messageDTO.setFrom(from);
        messageDTO.setContent(message);

        return add(brand, MemoryType.MESSAGE, messageDTO);
    }

    public Uni<String> add(String brand, MemoryType memoryType, IMemoryContentDTO content) {
        UUID id = UUID.randomUUID();
        ZonedDateTime now = ZonedDateTime.now();

        return Uni.createFrom().item(() -> {
            ConcurrentMap<String, MemoryDTO> brandMap = memories.computeIfAbsent(brand, k -> new ConcurrentHashMap<>());

            List<MemoryDTO> typeMemories = brandMap.values().stream()
                    .filter(dto -> dto.getMemoryType() == memoryType)
                    .sorted((m1, m2) -> m1.getRegDate().compareTo(m2.getRegDate()))
                    .collect(Collectors.toList());

            int maxEntries = getMaxEntriesForType(memoryType);
            while (typeMemories.size() >= maxEntries) {
                MemoryDTO oldest = typeMemories.remove(0);
                brandMap.remove(oldest.getId().toString());
            }

            MemoryDTO dto = new MemoryDTO();
            dto.setId(id);
            dto.setBrand(brand);
            dto.setMemoryType(memoryType);
            List<IMemoryContentDTO> contentList = new ArrayList<>();
            contentList.add(content);
            dto.setContent(contentList);
            dto.setRegDate(now);
            dto.setLastModifiedDate(now);
            brandMap.put(id.toString(), dto);
            return id.toString();
        });
    }

    public Uni<MemoryDTO> upsert(String id, MemoryDTO dto, IUser user) {
        List<IMemoryContentDTO> list = dto.getContent();
        if (list == null || list.isEmpty()) {
            return Uni.createFrom().failure(new IllegalArgumentException("Content list is empty"));
        }

        IMemoryContentDTO first = list.get(0);
        return add(dto.getBrand(), dto.getMemoryType(), first)
                .map(resultId -> {
                    dto.setId(UUID.fromString(resultId));
                    return dto;
                });
    }

    public Uni<Integer> updateHistory(String brand, SongIntroductionDTO dto) {
        return Uni.createFrom().item(() -> {
            ConversationHistoryDTO historyDTO = new ConversationHistoryDTO();
            historyDTO.setArtist(dto.getArtist());
            historyDTO.setTitle(dto.getTitle());
            historyDTO.setContent(dto.getContent());
            add(brand, MemoryType.CONVERSATION_HISTORY, historyDTO).subscribe().asCompletionStage();
            return 1;
        });
    }

    public Uni<Integer> delete(String id) {
        return Uni.createFrom().item(() -> {
            for (ConcurrentMap<String, MemoryDTO> brandMap : memories.values()) {
                if (brandMap.remove(id) != null) {
                    return 1;
                }
            }
            return 0;
        });
    }

    public Uni<Integer> deleteByBrand(String brand) {
        return Uni.createFrom().item(() -> {
            ConcurrentMap<String, MemoryDTO> brandMap = memories.remove(brand);
            if (brandMap != null) {
                return brandMap.size();
            }
            return 0;
        });
    }

    public Uni<List<MemoryDTO>> retrieveAndRemoveInstantMessages(String brand) {
        ConcurrentMap<String, MemoryDTO> brandMap = memories.get(brand);
        if (brandMap == null) {
            return Uni.createFrom().item(List.of());
        }

        List<MemoryDTO> messagesToRemove = brandMap.values().stream()
                .filter(dto -> dto.getMemoryType() == MemoryType.MESSAGE)
                .toList();

        if (messagesToRemove.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }

        List<Uni<MemoryDTO>> uniList = messagesToRemove.stream()
                .map(this::enrichDTO)
                .collect(Collectors.toList());

        messagesToRemove.forEach(dto -> brandMap.remove(dto.getId().toString()));

        return Uni.join().all(uniList).andFailFast();
    }

    public Uni<Integer> resetMemory(String brand, MemoryType memoryType) {
        return Uni.createFrom().item(() -> {
            ConcurrentMap<String, MemoryDTO> brandMap = memories.get(brand);
            if (brandMap != null) {
                List<String> toRemove = brandMap.entrySet().stream()
                        .filter(entry -> entry.getValue().getMemoryType() == memoryType)
                        .map(Map.Entry::getKey)
                        .toList();

                toRemove.forEach(brandMap::remove);
                return toRemove.size();
            }
            return 0;
        });
    }

    private int getMaxEntriesForType(MemoryType memoryType) {
        return switch (memoryType) {
            case CONVERSATION_HISTORY -> MAX_CONVERSATION_HISTORY;
            case EVENT -> MAX_EVENTS;
            case MESSAGE -> MAX_MESSAGES;
            default -> 5;
        };
    }

    private Uni<List<MemoryDTO>> flattenMemories() {
        List<Uni<MemoryDTO>> uniList = new ArrayList<>();
        for (ConcurrentMap<String, MemoryDTO> brandMap : memories.values()) {
            for (MemoryDTO dto : brandMap.values()) {
                uniList.add(enrichDTO(dto));
            }
        }
        if (uniList.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }
        return Uni.join().all(uniList).andFailFast();
    }

    private Uni<MemoryDTO> enrichDTO(MemoryDTO dto) {
        return radioStationService.getBySlugName(dto.getBrand())
                .map(radioStation -> {
                    dto.setColor(radioStation.getColor());
                    return dto;
                })
                .onFailure().recoverWithItem(dto);
    }
}