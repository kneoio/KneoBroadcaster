package io.kneo.broadcaster.model;

import io.kneo.broadcaster.model.cnst.PlaylistItemType;

import java.nio.file.Path;
import java.util.UUID;

public interface PlaylistItem {
    UUID getId();
    Path getFilePath();
    String getMetadata();
    int getPriority();
    PlaylistItemType getType();
}