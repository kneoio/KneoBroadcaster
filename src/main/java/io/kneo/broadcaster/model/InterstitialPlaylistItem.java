package io.kneo.broadcaster.model;

import io.kneo.broadcaster.model.cnst.PlaylistItemType;

import java.nio.file.Path;
import java.util.UUID;

public class InterstitialPlaylistItem implements PlaylistItem {
    private final UUID id;
    private final Path filePath;
    private final String metadata;
    private final PlaylistItemType type;
    private final int priority;

    public InterstitialPlaylistItem(UUID id, Path filePath, String metadata,
                                    PlaylistItemType type, int priority) {
        this.id = id;
        this.filePath = filePath;
        this.metadata = metadata;
        this.type = type;
        this.priority = priority;
    }

    public InterstitialPlaylistItem(Path filePath, String metadata,
                                    PlaylistItemType type, int priority) {
        this(UUID.randomUUID(), filePath, metadata, type, priority);
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public Path getFilePath() {
        return filePath;
    }

    @Override
    public String getMetadata() {
        return metadata;
    }

    @Override
    public int getPriority() {
        return priority;
    }

    @Override
    public PlaylistItemType getType() {
        return type;
    }
}