package io.kneo.broadcaster.service;

import io.kneo.broadcaster.dto.aihelper.SongIntroductionDTO;
import io.kneo.broadcaster.dto.memory.MemoryDTO;
import io.kneo.broadcaster.model.cnst.MemoryType;
import io.kneo.broadcaster.model.memory.AudienceContext;
import io.kneo.broadcaster.model.memory.ListenerContext;
import io.kneo.broadcaster.model.memory.Memory;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

@ApplicationScoped
public class MemoryService {

    private static final int MAX_CONVERSATION_HISTORY = 4;
    private static final int MAX_EVENTS = 3;
    private static final int MAX_INSTANT_MESSAGES = 1;

    @Inject
    ListenerService listenerService;

    @Inject
    ProfileService profileService;

    @Inject
    RadioStationService radioStationService;

    private final ConcurrentMap<String, ConcurrentMap<String, Memory>> memories = new ConcurrentHashMap<>();

    public Uni<List<MemoryDTO<?>>> getAll(final int limit, final int offset, final IUser user) {
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

    public Uni<List<MemoryDTO<?>>> getByBrandId(String brand, int limit, int offset) {
        ConcurrentMap<String, Memory> brandMap = memories.get(brand);
        if (brandMap == null) {
            return Uni.createFrom().item(List.of());
        }

        List<Uni<MemoryDTO<?>>> uniList = brandMap.values().stream()
                .map(this::mapToDTOWithColor)
                .collect(Collectors.toList());

        if (uniList.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }

        return Uni.join().all(uniList).andFailFast()
                .map(brandMemories -> brandMemories.stream()
                        .skip(offset)
                        .limit(limit > 0 ? limit : brandMemories.size())
                        .collect(Collectors.toList())
                );
    }

    public Uni<MemoryDTO<?>> getDTO(UUID id, IUser user, LanguageCode code) {
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
                    ConcurrentMap<String, Memory> brandMap = memories.get(brand);
                    if (brandMap != null) {
                        JsonArray introductions = new JsonArray();
                        brandMap.values().stream()
                                .filter(memory -> memory.getMemoryType() == MemoryType.CONVERSATION_HISTORY)
                                .forEach(memory -> {
                                    LinkedHashMap<String, Object> content = memory.getContent();
                                    Object historyData = content.get(MemoryType.CONVERSATION_HISTORY.getValue());
                                    if (historyData instanceof List<?> historyList) {
                                        historyList.forEach(intro -> {
                                            JsonObject introWithId = new JsonObject()
                                                    .put("id", memory.getId().toString())
                                                    .put("content", intro);
                                            introductions.add(introWithId);
                                        });
                                    }
                                });
                        result.put(MemoryType.CONVERSATION_HISTORY.getValue(), introductions);
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
                                AudienceContext audienceContext = new AudienceContext();
                                audienceContext.setName(profile.getName());
                                audienceContext.setDescription(profile.getDescription());
                                audienceContext.setCurrentMoment(TimeContextUtil.getCurrentMomentDetailed());

                                result.put(MemoryType.AUDIENCE_CONTEXT.getValue(), new JsonArray().add(audienceContext));
                                return null;
                            });
                    uniList.add(audienceUni);
                }
                case INSTANT_MESSAGE -> {
                    ConcurrentMap<String, Memory> brandMap = memories.get(brand);
                    if (brandMap != null) {
                        JsonArray messageArray = new JsonArray();
                        brandMap.values().stream()
                                .filter(memory -> memory.getMemoryType() == MemoryType.INSTANT_MESSAGE)
                                .forEach(memory -> {
                                    LinkedHashMap<String, Object> content = memory.getContent();
                                    Object messageData = content.get(MemoryType.INSTANT_MESSAGE.getValue());
                                    if (messageData instanceof List<?> messageList) {
                                        messageList.forEach(message -> {
                                            JsonObject messageWithId = new JsonObject()
                                                    .put("id", memory.getId().toString())
                                                    .put("content", message);
                                            messageArray.add(messageWithId);
                                        });
                                    }
                                });
                        result.put(MemoryType.INSTANT_MESSAGE.getValue(), messageArray);
                    }
                    if (!result.containsKey(MemoryType.INSTANT_MESSAGE.getValue())) {
                        result.put(MemoryType.INSTANT_MESSAGE.getValue(), new JsonArray());
                    }
                }
                case EVENT -> {
                    ConcurrentMap<String, Memory> brandMap = memories.get(brand);
                    if (brandMap != null) {
                        JsonArray eventArray = new JsonArray();
                        brandMap.values().stream()
                                .filter(memory -> memory.getMemoryType() == MemoryType.EVENT)
                                .findFirst()
                                .ifPresent(memory -> {
                                    LinkedHashMap<String, Object> content = memory.getContent();
                                    Object eventData = content.get(MemoryType.EVENT.getValue());
                                    if (eventData instanceof List<?> eventList) {
                                        if (!eventList.isEmpty()) {
                                            JsonObject eventWithId = new JsonObject()
                                                    .put("id", memory.getId().toString())
                                                    .put("content", eventList.get(0));
                                            eventArray.add(eventWithId);
                                        }
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

    public Uni<String> upsert(String brand, MemoryType memoryType, Object content) {
        UUID id = UUID.randomUUID();
        ZonedDateTime now = ZonedDateTime.now();

        return Uni.createFrom().item(() -> {
            ConcurrentMap<String, Memory> brandMap = memories.computeIfAbsent(brand, k -> new ConcurrentHashMap<>());

            if (memoryType == MemoryType.INSTANT_MESSAGE) {
                brandMap.entrySet().removeIf(entry ->
                        entry.getValue().getMemoryType() == MemoryType.INSTANT_MESSAGE);
            }

            List<Memory> typeMemories = brandMap.values().stream()
                    .filter(memory -> memory.getMemoryType() == memoryType)
                    .sorted((m1, m2) -> m1.getRegDate().compareTo(m2.getRegDate()))
                    .collect(Collectors.toList());

            int maxEntries = getMaxEntriesForType(memoryType);
            while (typeMemories.size() >= maxEntries) {
                Memory oldest = typeMemories.remove(0);
                brandMap.remove(oldest.getId().toString());
            }

            Memory memory = new Memory();
            memory.setId(id);
            memory.setBrand(brand);
            memory.setMemoryType(memoryType);

            LinkedHashMap<String, Object> structuredContent = new LinkedHashMap<>();
            List<Object> contentList = new ArrayList<>();
            contentList.add(content);
            structuredContent.put(memoryType.getValue(), contentList);

            memory.setContent(structuredContent);
            memory.setRegDate(now);
            memory.setLastModifiedDate(now);

            brandMap.put(id.toString(), memory);

            return id.toString();
        });
    }

    public Uni<MemoryDTO<?>> upsert(String id, MemoryDTO<?> dto, IUser user) {
        return upsert(dto.getBrand(), dto.getMemoryType(), dto.getContent())
                .map(resultId -> {
                    dto.setId(UUID.fromString(resultId));
                    return dto;
                });
    }

    public Uni<Integer> updateHistory(String brand, SongIntroductionDTO dto, IUser user) {
        return Uni.createFrom().item(() -> {
            LinkedHashMap<String, Object> introduction = new LinkedHashMap<>();
            introduction.put("title", dto.getTitle());
            introduction.put("artist", dto.getArtist());
            introduction.put("content", dto.getContent());

            upsert(brand, MemoryType.CONVERSATION_HISTORY, introduction).subscribe().asCompletionStage();
            return 1;
        });
    }

    public Uni<Integer> delete(String id) {
        return Uni.createFrom().item(() -> {
            for (ConcurrentMap<String, Memory> brandMap : memories.values()) {
                if (brandMap.remove(id) != null) {
                    return 1;
                }
            }
            return 0;
        });
    }

    public Uni<Integer> deleteByBrand(String brand) {
        return Uni.createFrom().item(() -> {
            ConcurrentMap<String, Memory> brandMap = memories.remove(brand);
            if (brandMap != null) {
                return brandMap.size();
            }
            return 0;
        });
    }

    public Uni<List<MemoryDTO<?>>> retrieveAndRemoveInstantMessages(String brand) {
        ConcurrentMap<String, Memory> brandMap = memories.get(brand);
        if (brandMap == null) {
            return Uni.createFrom().item(List.of());
        }

        List<Memory> messagesToRemove = brandMap.values().stream()
                .filter(memory -> memory.getMemoryType() == MemoryType.INSTANT_MESSAGE)
                .toList();

        if (messagesToRemove.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }

        List<Uni<MemoryDTO<?>>> uniList = messagesToRemove.stream()
                .map(this::mapToDTOWithColor)
                .collect(Collectors.toList());

        messagesToRemove.forEach(memory -> brandMap.remove(memory.getId().toString()));

        return Uni.join().all(uniList).andFailFast();
    }

    public Uni<List<MemoryDTO<?>>> peekInstantMessages(String brand) {
        ConcurrentMap<String, Memory> brandMap = memories.get(brand);
        if (brandMap == null) {
            return Uni.createFrom().item(List.of());
        }

        List<Uni<MemoryDTO<?>>> uniList = brandMap.values().stream()
                .filter(memory -> memory.getMemoryType() == MemoryType.INSTANT_MESSAGE)
                .map(this::mapToDTOWithColor)
                .collect(Collectors.toList());

        if (uniList.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }

        return Uni.join().all(uniList).andFailFast();
    }

    public Uni<List<MemoryDTO<?>>> retrieveAndRemoveEvents(String brand) {
        ConcurrentMap<String, Memory> brandMap = memories.get(brand);
        if (brandMap == null) {
            return Uni.createFrom().item(List.of());
        }

        List<Memory> eventsToRemove = brandMap.values().stream()
                .filter(memory -> memory.getMemoryType() == MemoryType.EVENT)
                .toList();

        if (eventsToRemove.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }

        List<Uni<MemoryDTO<?>>> uniList = eventsToRemove.stream()
                .map(this::mapToDTOWithColor)
                .collect(Collectors.toList());

        eventsToRemove.forEach(memory -> brandMap.remove(memory.getId().toString()));

        return Uni.join().all(uniList).andFailFast();
    }

    public Uni<List<MemoryDTO<?>>> peekEvents(String brand) {
        ConcurrentMap<String, Memory> brandMap = memories.get(brand);
        if (brandMap == null) {
            return Uni.createFrom().item(List.of());
        }

        List<Uni<MemoryDTO<?>>> uniList = brandMap.values().stream()
                .filter(memory -> memory.getMemoryType() == MemoryType.EVENT)
                .map(this::mapToDTOWithColor)
                .collect(Collectors.toList());

        if (uniList.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }

        return Uni.join().all(uniList).andFailFast();
    }

    public Uni<Integer> resetMemory(String brand, MemoryType memoryType) {
        return Uni.createFrom().item(() -> {
            ConcurrentMap<String, Memory> brandMap = memories.get(brand);
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
            case INSTANT_MESSAGE -> MAX_INSTANT_MESSAGES;
            default -> 5;
        };
    }

    private Uni<List<MemoryDTO<?>>> flattenMemories() {
        List<Uni<MemoryDTO<?>>> uniList = new ArrayList<>();
        for (ConcurrentMap<String, Memory> brandMap : memories.values()) {
            for (Memory memory : brandMap.values()) {
                uniList.add(mapToDTOWithColor(memory));
            }
        }
        if (uniList.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }
        return Uni.join().all(uniList).andFailFast();
    }

    private Uni<MemoryDTO<?>> mapToDTOWithColor(Memory memory) {
        return radioStationService.getBySlugName(memory.getBrand())
                .<MemoryDTO<?>>map(radioStation -> {
                    MemoryDTO<LinkedHashMap<String, Object>> dto = new MemoryDTO<>();
                    dto.setId(memory.getId());
                    dto.setBrand(memory.getBrand());
                    dto.setColor(radioStation.getColor());
                    dto.setMemoryType(memory.getMemoryType());
                    dto.setContent(memory.getContent());
                    dto.setRegDate(memory.getRegDate());
                    dto.setLastModifiedDate(memory.getLastModifiedDate());
                    return dto;
                })
                .onFailure().recoverWithItem(() -> {
                    MemoryDTO<LinkedHashMap<String, Object>> dto = new MemoryDTO<>();
                    dto.setId(memory.getId());
                    dto.setBrand(memory.getBrand());
                    dto.setMemoryType(memory.getMemoryType());
                    dto.setContent(memory.getContent());
                    dto.setRegDate(memory.getRegDate());
                    dto.setLastModifiedDate(memory.getLastModifiedDate());
                    return dto;
                });
    }
}