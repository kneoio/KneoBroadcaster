package io.kneo.broadcaster.service;

import io.kneo.broadcaster.dto.memory.AudienceContextDTO;
import io.kneo.broadcaster.dto.memory.EventInMemoryDTO;
import io.kneo.broadcaster.dto.memory.IMemoryContentDTO;
import io.kneo.broadcaster.dto.memory.ListenerContextDTO;
import io.kneo.broadcaster.dto.memory.MemoryDTO;
import io.kneo.broadcaster.dto.memory.MemoryResult;
import io.kneo.broadcaster.dto.memory.MessageDTO;
import io.kneo.broadcaster.model.cnst.EventType;
import io.kneo.broadcaster.model.cnst.MemoryType;
import io.kneo.broadcaster.util.TimeContextUtil;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.IUser;
import io.kneo.core.model.user.SuperUser;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

@ApplicationScoped
public class MemoryService {
    private static final int MAX_CONVERSATION_HISTORY = 3;
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
                        .sorted(Comparator.comparing(MemoryDTO::getRegDate))
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
                        .findFirst()
                        .orElse(null)
        );
    }

    public Uni<MemoryResult> getByType(String brand, String... types) {
        MemoryResult result = new MemoryResult();
        List<Uni<Void>> uniList = new ArrayList<>();
        ConcurrentMap<String, MemoryDTO> brandMap = memories.computeIfAbsent(brand, k -> new ConcurrentHashMap<>());

        for (String type : types) {
            MemoryType memoryType = MemoryType.valueOf(type);

            switch (memoryType) {
                case AUDIENCE_CONTEXT -> {
                    Uni<Void> audienceUni = getAudienceContext(brand, brandMap, result);
                    if (audienceUni != null) {
                        uniList.add(audienceUni);
                    }
                }
                case LISTENER_CONTEXT -> {
                    Uni<Void> listenerUni = getListenerContext(brand, brandMap, result);
                    if (listenerUni != null) {
                        uniList.add(listenerUni);
                    }
                }
                case MESSAGE, EVENT, CONVERSATION_HISTORY -> collectContents(brandMap, memoryType, result);
            }
        }

        if (uniList.isEmpty()) {
            return Uni.createFrom().item(result);
        }
        return Uni.combine().all().unis(uniList).with(r -> result);
    }


    public Uni<String> addEvent(String brand, EventType type, String timestamp, String description) {
        EventInMemoryDTO eventDTO = new EventInMemoryDTO();
        eventDTO.setType(type);
        eventDTO.setTriggerTime(timestamp);
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
        content.setId(id);
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

    private void collectContents(ConcurrentMap<String, MemoryDTO> brandMap,
                                 MemoryType type,
                                 MemoryResult result) {
        List<IMemoryContentDTO> list = new ArrayList<>();
        brandMap.values().stream()
                .filter(dto -> dto.getMemoryType() == type)
                .sorted((a, b) -> a.getRegDate().compareTo(b.getRegDate()))
                .forEach(dto -> {
                    if (dto.getContent() != null) {
                        list.addAll(dto.getContent());
                    }
                });

        switch (type) {
            case MESSAGE -> result.setMessages((List) list);
            case EVENT -> result.setEvents((List) list);
            case CONVERSATION_HISTORY -> result.setConversationHistory((List) list);
        }
    }

    private Uni<Void> getAudienceContext(String brand,
                                         ConcurrentMap<String, MemoryDTO> brandMap,
                                         MemoryResult result) {
        MemoryDTO cached = brandMap.values().stream()
                .filter(dto -> dto.getMemoryType() == MemoryType.AUDIENCE_CONTEXT)
                .findFirst()
                .orElse(null);

        if (cached != null && cached.getContent() != null && !cached.getContent().isEmpty()) {
            AudienceContextDTO dto = (AudienceContextDTO) cached.getContent().get(0);
            return radioStationService.getBySlugName(brand)
                    .map(radioStation -> {
                        ZoneId zoneId = radioStation.getTimeZone();
                        dto.setCurrentMoment(TimeContextUtil.getCurrentMomentDetailed(zoneId));
                        result.setAudienceContext(List.of(dto));
                        return null;
                    });
        }

        return radioStationService.getBySlugName(brand)
                .chain(radioStation -> {
                    ZoneId zoneId = radioStation.getTimeZone();
                    return profileService.getById(radioStation.getProfileId())
                            .map(profile -> {
                                AudienceContextDTO dto = new AudienceContextDTO();
                                dto.setName(profile.getName());
                                dto.setDescription(profile.getDescription());
                                dto.setCurrentMoment(TimeContextUtil.getCurrentMomentDetailed(zoneId));

                                add(brand, MemoryType.AUDIENCE_CONTEXT, dto).subscribe().asCompletionStage();
                                result.setAudienceContext(List.of(dto));
                                return null;
                            });
                });
    }


    private Uni<Void> getListenerContext(String brand,
                                         ConcurrentMap<String, MemoryDTO> brandMap,
                                         MemoryResult result) {
        MemoryDTO cached = brandMap.values().stream()
                .filter(dto -> dto.getMemoryType() == MemoryType.LISTENER_CONTEXT)
                .findFirst()
                .orElse(null);

        if (cached != null && cached.getContent() != null && !cached.getContent().isEmpty()) {
            result.setListenerContext((List) cached.getContent());
            return null;
        }

        return listenerService.getBrandListenersEntities(brand, 1000, 0, SuperUser.build())
                .map(brandListeners -> {
                    List<ListenerContextDTO> listeners = brandListeners.stream()
                            .map(bl -> {
                                ListenerContextDTO dto = new ListenerContextDTO();
                                dto.setName(bl.getListener().getLocalizedName().get(LanguageCode.en));
                                String nickname = bl.getListener().getNickName().get(LanguageCode.en);
                                if (nickname != null) {
                                    dto.setNickname(nickname);
                                }
                                dto.setLocation(bl.getListener().getCountry().getCountryName());
                                return dto;
                            })
                            .toList();

                    if (!listeners.isEmpty()) {
                        UUID id = UUID.randomUUID();
                        ZonedDateTime now = ZonedDateTime.now();

                        MemoryDTO dto = new MemoryDTO();
                        dto.setId(id);
                        dto.setBrand(brand);
                        dto.setMemoryType(MemoryType.LISTENER_CONTEXT);
                        dto.setContent(new ArrayList<>(listeners));
                        dto.setRegDate(now);
                        dto.setLastModifiedDate(now);

                        brandMap.put(id.toString(), dto);
                    }

                    result.setListenerContext(new ArrayList<>(listeners));
                    return null;
                });
    }


}