package io.kneo.broadcaster.service;

import io.kneo.broadcaster.model.ConversationMemory;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class MemoryService {

    private final Map<String, ConversationMemory> memoryStore = new ConcurrentHashMap<>();

    public Uni<ConversationMemory> getMemory(String brand) {
        return Uni.createFrom().item(() ->
                memoryStore.computeIfAbsent(brand, k -> new ConversationMemory())
        );
    }

    public Uni<ConversationMemory> saveMemory(String brand, ConversationMemory memory) {
        return Uni.createFrom().item(() -> {
            memoryStore.put(brand, memory);
            return memory;
        });
    }

    public Uni<Void> clearMemory(String brand) {
        return Uni.createFrom().item(() -> {
            memoryStore.remove(brand);
            return null;
        });
    }
}