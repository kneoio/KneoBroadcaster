package io.kneo.broadcaster.service.chat;

import java.time.Instant;

record PublicChatSession(String email, Instant expiresAt) {

    boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }
}
