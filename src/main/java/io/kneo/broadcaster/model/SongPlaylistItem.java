package io.kneo.broadcaster.model;

import io.kneo.broadcaster.model.cnst.PlaylistItemType;

import java.nio.file.Path;
import java.util.UUID;

public class SongPlaylistItem implements PlaylistItem {
    private final UUID id;
    private final Path filePath;
    private final String metadata;
    private final PlaylistItemType type;
    private final int priority;
    private final SoundFragment fragment;

    public SongPlaylistItem(UUID id, Path filePath, String metadata,
                            PlaylistItemType type, int priority) {
        this.id = id;
        this.filePath = filePath;
        this.metadata = metadata;
        this.type = type;
        this.priority = priority;
        this.fragment = null;
    }

    public SongPlaylistItem(SoundFragment fragment) {
        this.fragment = fragment;
        this.id = fragment.getId();
        this.filePath = fragment.getFilePath();
        this.metadata = String.format("%s - %s", fragment.getArtist(), fragment.getTitle());
        this.type = PlaylistItemType.SONG;
        this.priority = 100;
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

    public SoundFragment getFragment() {
        return fragment;
    }
}