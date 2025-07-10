package io.kneo.broadcaster.service;

import io.kneo.broadcaster.dto.aihelper.SongIntroductionDTO;
import io.kneo.broadcaster.dto.memory.MemoryDTO;
import io.kneo.broadcaster.model.cnst.MemoryType;
import io.kneo.broadcaster.model.memory.AudienceContext;
import io.kneo.broadcaster.model.memory.ListenerContext;
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
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

@ApplicationScoped
public class MemoryService {

    private static final int MEMORY_RETENTION_HOURS = 24;
    private static final int MAX_ENTRIES_PER_TYPE = 5;

    @Inject
    ListenerService listenerService;

    @Inject
    ProfileService profileService;

    @Inject
    RadioStationService radioStationService;

    private final ConcurrentMap<String, ConcurrentMap<MemoryType, List<MemoryDTO<?>>>> memories = new ConcurrentHashMap<>();

    public Uni<List<MemoryDTO<?>>> getAll(final int limit, final int offset, final IUser user) {
        return Uni.createFrom().item(() -> {
            List<MemoryDTO<?>> allMemories = flattenMemories();
            return allMemories.stream()
                    .skip(offset)
                    .limit(limit > 0 ? limit : allMemories.size())
                    .collect(Collectors.toList());
        });
    }

    public Uni<Integer> getAllCount(IUser user) {
        return Uni.createFrom().item(() -> {
            return flattenMemories().size();
        });
    }

    public Uni<List<MemoryDTO<?>>> getByBrandId(String brand, int limit, int offset) {
        return Uni.createFrom().item(() -> {
            List<MemoryDTO<?>> brandMemories = new ArrayList<>();
            ConcurrentMap<MemoryType, List<MemoryDTO<?>>> brandMap = memories.get(brand);
            if (brandMap != null) {
                brandMap.values().forEach(brandMemories::addAll);
            }
            return brandMemories.stream()
                    .skip(offset)
                    .limit(limit > 0 ? limit : brandMemories.size())
                    .collect(Collectors.toList());
        });
    }

    public Uni<MemoryDTO<?>> getDTO(UUID id, IUser user, LanguageCode code) {
        return Uni.createFrom().item(() -> {
            return flattenMemories().stream()
                    .filter(memory -> id.equals(memory.getId()))
                    .findFirst()
                    .orElse(null);
        });
    }

    public Uni<JsonObject> getByType(String brand, String... types) {
        JsonObject result = new JsonObject();
        List<Uni<Void>> uniList = new ArrayList<>();

        for (String type : types) {
            MemoryType memoryType = MemoryType.valueOf(type);

            switch (memoryType) {
                case CONVERSATION_HISTORY -> {
                    ConcurrentMap<MemoryType, List<MemoryDTO<?>>> brandMap = memories.get(brand);
                    if (brandMap != null) {
                        List<MemoryDTO<?>> memories = brandMap.get(MemoryType.CONVERSATION_HISTORY);
                        if (memories != null) {
                            JsonArray introductions = new JsonArray();
                            memories.forEach(memory -> {
                                JsonObject content = (JsonObject) memory.getContent();
                                if (content.containsKey("introductions")) {
                                    JsonArray memoryIntroductions = content.getJsonArray("introductions");
                                    memoryIntroductions.forEach(introductions::add);
                                }
                            });
                            result.put("introductions", introductions);
                        }
                    }
                    if (!result.containsKey("introductions")) {
                        result.put("introductions", new JsonArray());
                    }
                }
                case LISTENER_CONTEXTS -> {
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

                                result.put("listeners", new JsonArray(listeners));
                                return null;
                            });
                    uniList.add(listenerUni);
                }
                case AUDIENCE_CONTEXT -> {
                    Uni<Void> audienceUni = radioStationService.findByBrandName(brand)
                            .chain(radioStation -> profileService.getById(radioStation.getProfileId()))
                            .map(profile -> {
                                AudienceContext audienceContext = new AudienceContext();
                                audienceContext.setName(profile.getName());
                                audienceContext.setDescription(profile.getDescription());
                                audienceContext.setCurrentMoment(TimeContextUtil.getCurrentMomentDetailed());

                                result.put("environment", new JsonArray().add(audienceContext));
                                return null;
                            });
                    uniList.add(audienceUni);
                }
                case INSTANT_MESSAGE -> {
                    ConcurrentMap<MemoryType, List<MemoryDTO<?>>> brandMap = memories.get(brand);
                    if (brandMap != null) {
                        List<MemoryDTO<?>> messages = brandMap.get(MemoryType.INSTANT_MESSAGE);
                        if (messages != null && !messages.isEmpty()) {
                            MemoryDTO<?> latestMessage = messages.get(messages.size() - 1);
                            result.put("message", latestMessage.getContent());
                        }
                    }
                    if (!result.containsKey("message")) {
                        result.put("message", new JsonObject());
                    }
                }
            }
        }

        if (uniList.isEmpty()) {
            return Uni.createFrom().item(result);
        }

        return Uni.combine().all().unis(uniList).combinedWith(results -> result);
    }

    public Uni<MemoryDTO<?>> upsert(String id, MemoryDTO<?> dto, IUser user) {
        if (dto.getId() == null) {
            dto.setId(UUID.randomUUID());
        }

        dto.setRegDate(ZonedDateTime.now());
        dto.setLastModifiedDate(ZonedDateTime.now());

        return Uni.createFrom().item(() -> {
            memories.compute(dto.getBrand(), (brand, existingBrandMap) -> {
                ConcurrentMap<MemoryType, List<MemoryDTO<?>>> map = existingBrandMap != null ?
                        existingBrandMap : new ConcurrentHashMap<>();

                map.compute(dto.getMemoryType(), (type, list) -> {
                    List<MemoryDTO<?>> memoryList = list != null ?
                            new LinkedList<>(list) : new LinkedList<>();
                    if (dto.getMemoryType() == MemoryType.INSTANT_MESSAGE) {
                        memoryList.clear(); // Remove all existing messages
                    }

                    memoryList.add(dto);
                    if (dto.getMemoryType() == MemoryType.CONVERSATION_HISTORY) {
                        while (memoryList.size() > MAX_ENTRIES_PER_TYPE) {
                            memoryList.remove(0);
                        }
                    }
                    return memoryList;
                });
                return map;
            });
            return dto;
        });
    }

    public Uni<Integer> patch(String brand, SongIntroductionDTO dto, IUser user) {
        return Uni.createFrom().item(() -> {
            MemoryDTO<JsonObject> memory = new MemoryDTO<>();
            memory.setId(UUID.randomUUID());
            memory.setBrand(brand);
            memory.setMemoryType(MemoryType.CONVERSATION_HISTORY);
            memory.setRegDate(ZonedDateTime.now());
            memory.setLastModifiedDate(ZonedDateTime.now());

            JsonObject introduction = new JsonObject()
                    .put("title", dto.getTitle())
                    .put("artist", dto.getArtist())
                    .put("content", dto.getContent());

            memory.setContent(new JsonObject()
                    .put("introductions", new JsonArray().add(introduction)));
            upsert(memory.getId().toString(), memory, user).subscribe().asCompletionStage();
            return 1;
        });
    }

    public Uni<Integer> delete(String id) {
        return Uni.createFrom().item(() -> {
            UUID memoryId = UUID.fromString(id);

            for (ConcurrentMap<MemoryType, List<MemoryDTO<?>>> brandMap : memories.values()) {
                for (List<MemoryDTO<?>> memoryList : brandMap.values()) {
                    if (memoryList.removeIf(memory -> memoryId.equals(memory.getId()))) {
                        return 1;
                    }
                }
            }
            return 0;
        });
    }

    public Uni<Integer> deleteByBrand(String brand) {
        return Uni.createFrom().item(() -> {
            ConcurrentMap<MemoryType, List<MemoryDTO<?>>> brandMap = memories.remove(brand);
            if (brandMap != null) {
                return brandMap.values().stream()
                        .mapToInt(List::size)
                        .sum();
            }
            return 0;
        });
    }

    public Uni<List<MemoryDTO<?>>> retrieveAndRemoveInstantMessages(String brand) {
        return Uni.createFrom().item(() -> {
            ConcurrentMap<MemoryType, List<MemoryDTO<?>>> brandMap = memories.get(brand);
            if (brandMap != null) {
                List<MemoryDTO<?>> messages = brandMap.remove(MemoryType.INSTANT_MESSAGE);
                return messages != null ? messages : List.of();
            }
            return List.of();
        });
    }

    public Uni<List<MemoryDTO<?>>> peekInstantMessages(String brand) {
        return Uni.createFrom().item(() -> {
            ConcurrentMap<MemoryType, List<MemoryDTO<?>>> brandMap = memories.get(brand);
            if (brandMap != null) {
                List<MemoryDTO<?>> messages = brandMap.get(MemoryType.INSTANT_MESSAGE);
                return messages != null ? new ArrayList<>(messages) : List.of();
            }
            return List.of();
        });
    }

    private List<MemoryDTO<?>> flattenMemories() {
        List<MemoryDTO<?>> allMemories = new ArrayList<>();
        for (ConcurrentMap<MemoryType, List<MemoryDTO<?>>> brandMap : memories.values()) {
            for (List<MemoryDTO<?>> memoryList : brandMap.values()) {
                allMemories.addAll(memoryList);
            }
        }
        return allMemories;
    }
}