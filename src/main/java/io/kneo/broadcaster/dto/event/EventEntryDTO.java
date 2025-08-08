package io.kneo.broadcaster.dto.event;

import java.util.UUID;

public record EventEntryDTO(UUID id, String brand, String type, String priority, String description){}

