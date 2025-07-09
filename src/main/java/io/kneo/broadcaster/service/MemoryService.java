package io.kneo.broadcaster.service;

import io.kneo.broadcaster.dto.aihelper.SongIntroductionDTO;
import io.kneo.broadcaster.dto.memory.MemoryDTO;
import io.kneo.broadcaster.model.cnst.MemoryType;
import io.kneo.broadcaster.model.memory.AudienceContext;
import io.kneo.broadcaster.model.memory.ListenerContext;

import io.kneo.broadcaster.util.TimeContextUtil;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.IUser;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

@ApplicationScoped
public class MemoryService {

    private static final int MEMORY_RETENTION_HOURS = 24;

    @Inject
    ListenerService listenerService;

    @Inject
    ProfileService profileService;

    @Inject
    RadioStationService radioStationService;

    private final ConcurrentMap<String, List<MemoryDTO<?>>> instantMessages = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, MemoryDTO<?>> conversationHistories = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<UUID>> brandToMemoryIds = new ConcurrentHashMap<>();

    public Uni<List<MemoryDTO<?>>> getAll(final int limit, final int offset, final IUser user) {
        return Uni.createFrom().item(() -> {
            List<MemoryDTO<?>> allMemories = new ArrayList<>();
            
            // Add conversation histories
            allMemories.addAll(conversationHistories.values());
            
            // Add instant messages
            instantMessages.forEach((brand, memories) -> {
                allMemories.addAll(memories);
            });
            
            return allMemories.stream()
                    .skip(offset)
                    .limit(limit > 0 ? limit : allMemories.size())
                    .collect(Collectors.toList());
        });
    }

    public Uni<Long> getAllCount() {
        return Uni.createFrom().item(() -> {
            long count = conversationHistories.size();
            count += instantMessages.values().stream().mapToLong(List::size).sum();
            return count;
        });
    }

    public Uni<List<MemoryDTO<?>>> getAll(int limit, int offset) {
        return Uni.createFrom().item(() -> {
            List<MemoryDTO<?>> allMemories = new ArrayList<>();
            
            // Add conversation histories
            allMemories.addAll(conversationHistories.values());
            
            // Add instant messages
            instantMessages.forEach((brand, memories) -> {
                allMemories.addAll(memories);
            });
            
            return allMemories.stream()
                    .skip(offset)
                    .limit(limit > 0 ? limit : allMemories.size())
                    .collect(Collectors.toList());
        });
    }

    public Uni<List<MemoryDTO<?>>> getByBrandId(String brand, int limit, int offset) {
        return Uni.createFrom().item(() -> {
            List<MemoryDTO<?>> allMemories = new ArrayList<>();
            
            // Add conversation histories for this brand
            List<UUID> brandMemoryIds = brandToMemoryIds.get(brand);
            if (brandMemoryIds != null) {
                brandMemoryIds.stream()
                        .map(conversationHistories::get)
                        .filter(memory -> memory != null)
                        .forEach(allMemories::add);
            }
            
            // Add instant messages for this brand
            List<MemoryDTO<?>> brandInstantMessages = instantMessages.get(brand);
            if (brandInstantMessages != null) {
                allMemories.addAll(brandInstantMessages);
            }
            
            return allMemories.stream()
                    .skip(offset)
                    .limit(limit > 0 ? limit : allMemories.size())
                    .collect(Collectors.toList());
        });
    }

    public Uni<MemoryDTO<?>> getDTO(UUID id, IUser user, LanguageCode code) {
        return Uni.createFrom().item(() -> {
            return conversationHistories.get(id);
        });
    }

    public Uni<List<MemoryDTO<?>>> getByType(String brand, String type, IUser user) {
        MemoryType memoryType = MemoryType.valueOf(type);

        return switch (memoryType) {
            case CONVERSATION_HISTORY -> Uni.createFrom().item(() -> {
                        List<UUID> brandMemoryIds = brandToMemoryIds.get(brand);
                        if (brandMemoryIds == null || brandMemoryIds.isEmpty()) {
                            // Return empty MemoryDTO for conversation history
                            MemoryDTO<JsonObject> emptyMemory = new MemoryDTO<>();
                            emptyMemory.setBrand(brand);
                            emptyMemory.setMemoryType(MemoryType.CONVERSATION_HISTORY);
                            emptyMemory.setContent(new JsonObject().put(memoryType.getValue(), new JsonArray()));
                            emptyMemory.setRegDate(ZonedDateTime.now());
                            emptyMemory.setLastModifiedDate(ZonedDateTime.now());
                            return List.of(emptyMemory);
                        }
                        return brandMemoryIds.stream()
                                .map(conversationHistories::get)
                                .filter(memory -> memory != null && memory.getMemoryType() == memoryType)
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

                        MemoryDTO<JsonObject> memoryDTO = new MemoryDTO<>();
                        memoryDTO.setBrand(brand);
                        memoryDTO.setMemoryType(MemoryType.LISTENER_CONTEXTS);
                        memoryDTO.setContent(new JsonObject().put(memoryType.getValue(), new JsonArray(listeners)));
                        memoryDTO.setRegDate(ZonedDateTime.now());
                        memoryDTO.setLastModifiedDate(ZonedDateTime.now());

                        return List.of(memoryDTO);
                    });
            case AUDIENCE_CONTEXT -> radioStationService.findByBrandName(brand)
                    .chain(radioStation -> profileService.getById(radioStation.getProfileId()))
                    .map(profile -> {
                        AudienceContext audienceContext = new AudienceContext();
                        audienceContext.setName(profile.getName());
                        audienceContext.setDescription(profile.getDescription());
                        audienceContext.setCurrentMoment(TimeContextUtil.getCurrentMomentDetailed());
                        MemoryDTO<JsonObject> memoryDTO = new MemoryDTO<>();
                        memoryDTO.setBrand(brand);
                        memoryDTO.setMemoryType(MemoryType.AUDIENCE_CONTEXT);
                        memoryDTO.setContent(new JsonObject().put(memoryType.getValue(), new JsonArray().add(audienceContext)));
                        memoryDTO.setRegDate(ZonedDateTime.now());
                        memoryDTO.setLastModifiedDate(ZonedDateTime.now());
                        return List.of(memoryDTO);
                    });
            case INSTANT_MESSAGE -> {
                List<MemoryDTO<?>> messages = instantMessages.getOrDefault(brand, List.of());
                if (messages.isEmpty()) {
                    MemoryDTO<JsonObject> emptyMemory = new MemoryDTO<>();
                    emptyMemory.setBrand(brand);
                    emptyMemory.setMemoryType(MemoryType.INSTANT_MESSAGE);
                    emptyMemory.setContent(new JsonObject().put(memoryType.getValue(), new JsonArray()));
                    emptyMemory.setRegDate(ZonedDateTime.now());
                    emptyMemory.setLastModifiedDate(ZonedDateTime.now());
                    yield Uni.createFrom().item(List.of(emptyMemory));
                }
                yield Uni.createFrom().item(messages);
            }
            default -> throw new IllegalArgumentException("Unsupported memory type: " + memoryType);
        };
    }

    public Uni<MemoryDTO<?>> upsert(String id, MemoryDTO<?> dto, IUser user) {
        if (dto.getMemoryType() == MemoryType.INSTANT_MESSAGE) {
            JsonObject content = (dto.getContent() instanceof JsonObject) ? 
                (JsonObject) dto.getContent() : 
                new JsonObject(dto.getContent().toString());
            return storeInstantMessage(dto.getBrand(), content)
                    .onItem().transform(v -> {
                        // Create a new MemoryDTO for instant message
                        MemoryDTO<JsonObject> memoryDTO = new MemoryDTO<>();
                        memoryDTO.setBrand(dto.getBrand());
                        memoryDTO.setMemoryType(MemoryType.INSTANT_MESSAGE);
                        memoryDTO.setContent(content);
                        memoryDTO.setRegDate(ZonedDateTime.now());
                        memoryDTO.setLastModifiedDate(ZonedDateTime.now());
                        return memoryDTO;
                    });
        } else {
            // Work directly with MemoryDTO
            MemoryDTO<?> memoryDTO = dto;
            UUID memoryId;
            if (id == null) {
                // Insert new memory
                memoryId = UUID.randomUUID();
            } else {
                // Update existing memory
                memoryId = UUID.fromString(id);
            }
            
            // Set timestamps
            memoryDTO.setRegDate(ZonedDateTime.now());
            memoryDTO.setLastModifiedDate(ZonedDateTime.now());
            
            // Store in memory
            conversationHistories.put(memoryId, memoryDTO);
            
            // Update brand mapping
            brandToMemoryIds.compute(memoryDTO.getBrand(), (brand, ids) -> {
                List<UUID> idList = ids != null ? new ArrayList<>(ids) : new ArrayList<>();
                if (!idList.contains(memoryId)) {
                    idList.add(memoryId);
                }
                return idList;
            });
            
            return Uni.createFrom().item(memoryDTO);
        }
    }

    public Uni<Integer> patch(String brand, SongIntroductionDTO dto, IUser user) {
        return Uni.createFrom().item(() -> {
            // Find existing conversation history for this brand
            List<UUID> brandMemoryIds = brandToMemoryIds.get(brand);
            if (brandMemoryIds != null) {
                for (UUID memoryId : brandMemoryIds) {
                    MemoryDTO<?> memory = conversationHistories.get(memoryId);
                    if (memory != null && memory.getMemoryType() == MemoryType.CONVERSATION_HISTORY) {
                        // Update the conversation history with new introduction
                        JsonObject content = (JsonObject) memory.getContent();
                        JsonArray introductions = content.getJsonArray("introductions");
                        if (introductions == null) {
                            introductions = new JsonArray();
                        }
                        
                        JsonObject newIntroduction = new JsonObject()
                                .put("title", dto.getTitle())
                                .put("artist", dto.getArtist())
                                .put("content", dto.getContent());
                        
                        introductions.add(newIntroduction);
                        content.put("introductions", introductions);
                        ((MemoryDTO<JsonObject>) memory).setContent(content);
                        
                        return 1; // Updated 1 record
                    }
                }
            }
            
            // Create new conversation history if none exists
            MemoryDTO<JsonObject> newMemory = new MemoryDTO<>();
            UUID memoryId = UUID.randomUUID();
            newMemory.setBrand(brand);
            newMemory.setMemoryType(MemoryType.CONVERSATION_HISTORY);
            newMemory.setRegDate(ZonedDateTime.now());
            newMemory.setLastModifiedDate(ZonedDateTime.now());
            
            JsonObject newIntroduction = new JsonObject()
                    .put("title", dto.getTitle())
                    .put("artist", dto.getArtist())
                    .put("content", dto.getContent());
            
            JsonArray introductions = new JsonArray().add(newIntroduction);
            newMemory.setContent(new JsonObject().put("introductions", introductions));
            
            conversationHistories.put(memoryId, newMemory);
            brandToMemoryIds.compute(brand, (b, ids) -> {
                List<UUID> idList = ids != null ? new ArrayList<>(ids) : new ArrayList<>();
                idList.add(memoryId);
                return idList;
            });
            
            return 1; // Created 1 record
        });
    }

    public Uni<Integer> delete(String id) {
        return Uni.createFrom().item(() -> {
            UUID memoryId = UUID.fromString(id);
            MemoryDTO<?> removedMemory = conversationHistories.remove(memoryId);
            
            if (removedMemory != null) {
                // Remove from brand mapping
                brandToMemoryIds.computeIfPresent(removedMemory.getBrand(), (brand, ids) -> {
                    ids.remove(memoryId);
                    return ids.isEmpty() ? null : ids;
                });
                return 1; // Deleted 1 record
            }
            return 0; // No record found
        });
    }

    public Uni<Integer> deleteByBrand(String brand) {
        return Uni.createFrom().item(() -> {
            List<UUID> brandMemoryIds = brandToMemoryIds.remove(brand);
            int deletedCount = 0;
            
            if (brandMemoryIds != null) {
                for (UUID memoryId : brandMemoryIds) {
                    if (conversationHistories.remove(memoryId) != null) {
                        deletedCount++;
                    }
                }
            }
            
            // Also remove instant messages for this brand
            List<MemoryDTO<?>> instantMessagesForBrand = instantMessages.remove(brand);
            if (instantMessagesForBrand != null) {
                deletedCount += instantMessagesForBrand.size();
            }
            
            return deletedCount;
        });
    }

    public Uni<Void> storeInstantMessage(String brand, JsonObject message) {
        return Uni.createFrom().voidItem()
                .invoke(() -> {
                    MemoryDTO<JsonObject> memory = new MemoryDTO<>();
                    memory.setBrand(brand);
                    memory.setMemoryType(MemoryType.INSTANT_MESSAGE);
                    memory.setContent(message);
                    memory.setRegDate(ZonedDateTime.now());
                    memory.setLastModifiedDate(ZonedDateTime.now());
                    
                    instantMessages.compute(brand, (key, messages) -> {
                        List<MemoryDTO<?>> messageList = messages != null ? 
                                new ArrayList<>(messages) : 
                                new ArrayList<>();
                        messageList.add(memory);
                        return messageList;
                    });
                });
    }

    public Uni<List<MemoryDTO<?>>> retrieveAndRemoveInstantMessages(String brand) {
        return Uni.createFrom().item(() -> {
            List<MemoryDTO<?>> messages = instantMessages.remove(brand);
            return messages != null ? messages : List.of();
        });
    }

    public Uni<List<MemoryDTO<?>>> peekInstantMessages(String brand) {
        return Uni.createFrom().item(() -> {
            List<MemoryDTO<?>> messages = instantMessages.get(brand);
            return messages != null ? new ArrayList<>(messages) : List.of();
        });
    }

    /**
     * Clean up old conversation histories that exceed the retention threshold
     * This preserves the retention logic from the original repository implementation
     */
    public Uni<Integer> cleanupOldMemories() {
        return Uni.createFrom().item(() -> {
            ZonedDateTime cutoffTime = ZonedDateTime.now().minus(MEMORY_RETENTION_HOURS, ChronoUnit.HOURS);
            int cleanedCount = 0;
            
            List<UUID> toRemove = new ArrayList<>();
            
            for (MemoryDTO<?> memory : conversationHistories.values()) {
                ZonedDateTime lastModified = memory.getLastModifiedDate() != null ? 
                        memory.getLastModifiedDate() : memory.getRegDate();
                
                if (lastModified != null && lastModified.isBefore(cutoffTime)) {
                    toRemove.add(memory.getId());
                }
            }
            
            // Remove old memories
            for (UUID memoryId : toRemove) {
                MemoryDTO<?> removedMemory = conversationHistories.remove(memoryId);
                if (removedMemory != null) {
                    // Update brand mapping
                    brandToMemoryIds.computeIfPresent(removedMemory.getBrand(), (brand, ids) -> {
                        ids.remove(memoryId);
                        return ids.isEmpty() ? null : ids;
                    });
                    cleanedCount++;
                }
            }
            
            return cleanedCount;
        });
    }

    public Uni<Integer> getAllCount(IUser user) {
        return Uni.createFrom().item(() -> {
            int totalCount = conversationHistories.size();
            // Add instant messages count
            for (List<MemoryDTO<?>> messages : instantMessages.values()) {
                totalCount += messages.size();
            }
            return totalCount;
        });
    }


}