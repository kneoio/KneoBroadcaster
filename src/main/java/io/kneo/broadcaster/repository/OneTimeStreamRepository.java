package io.kneo.broadcaster.repository;

import io.kneo.broadcaster.model.stream.OneTimeStream;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
public class OneTimeStreamRepository {

    private final Map<String, OneTimeStream> inMemoryRepository = new HashMap<>();

    public Uni<OneTimeStream> getBySlugName(String slugName) {
        return Uni.createFrom().item(inMemoryRepository.get(slugName));
    }

    public void insert(OneTimeStream doc) {
        inMemoryRepository.put(doc.getSlugName(), doc);
    }
}
