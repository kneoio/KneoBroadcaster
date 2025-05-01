package io.kneo.broadcaster.service;

import io.kneo.broadcaster.model.ConversationMemory;
import io.kneo.broadcaster.repository.ConversationMemoryRepository;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class MemoryService {

    @Inject
    ConversationMemoryRepository repository;

    public Uni<List<ConversationMemory>> getAll(int limit, int offset) {
        return repository.getAll(limit, offset, null); // Assuming you might add user context later
    }

    public Uni<List<ConversationMemory>> getByBrandId(UUID brandId, int limit, int offset) {
        return repository.getByBrandId(brandId, limit, offset);
    }

    public Uni<ConversationMemory> getById(UUID id) {
        return repository.findById(id);
    }

    public Uni<ConversationMemory> create(ConversationMemory memory) {
        return repository.insert(memory);
    }

    public Uni<ConversationMemory> update(UUID id, ConversationMemory memory) {
        return repository.update(id, memory);
    }

    public Uni<Void> delete(UUID id) {
        return repository.delete(id)
                .onItem().transform(count -> null); // Convert Integer to Void
    }

    public Uni<Void> archive(UUID id) {
        return repository.archive(id)
                .onItem().transform(count -> null); // Convert Integer to Void
    }

    // Additional business logic methods can be added here
    public Uni<ConversationMemory> saveConversation(UUID brandId, String messageType, JsonObject content) {
        ConversationMemory memory = new ConversationMemory();
        memory.setBrandId(brandId);
        memory.setMessageType(messageType);
        memory.setContent(content);
        return create(memory);
    }

    public Uni<List<ConversationMemory>> getRecentConversations(UUID brandId, int count) {
        return getByBrandId(brandId, count, 0);
    }
}