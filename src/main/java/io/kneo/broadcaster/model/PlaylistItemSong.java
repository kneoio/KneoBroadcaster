package io.kneo.broadcaster.model;

import io.kneo.broadcaster.model.cnst.PlaylistItemType;

import java.nio.file.Path;
import java.util.UUID;

public class PlaylistItemSong implements PlaylistItem {
    private final Path filePath;
    private final String metadata;
    private final PlaylistItemType type;
    private final int priority;
    private final SoundFragment fragment;

    public PlaylistItemSong(UUID id, Path filePath, String metadata,
                            PlaylistItemType type, int priority) {
        this.filePath = filePath;
        this.metadata = metadata;
        this.type = type;
        this.priority = priority;
        this.fragment = null;
    }

    public PlaylistItemSong(SoundFragment fragment) {
        this.fragment = fragment;
        this.filePath = fragment.getFilePath();
        this.metadata = String.format("%s - %s", fragment.getArtist(), fragment.getTitle());
        this.type = PlaylistItemType.SONG;
        this.priority = 100;
    }

    @Override
    public UUID getId() {
        return fragment.getId();
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