package io.kneo.broadcaster.service.chat;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class PublicChatTokenService {
    private static final long TOKEN_EXPIRY_SECONDS = 86400 * 30;
    private final Map<String, UserToken> tokens = new ConcurrentHashMap<>();

    public String generateToken(long userId, String username) {
        String token = UUID.randomUUID().toString();
        tokens.put(token, new UserToken(userId, username, Instant.now().plusSeconds(TOKEN_EXPIRY_SECONDS)));
        return token;
    }

    public TokenValidationResult validateToken(String token) {
        UserToken userToken = tokens.get(token);
        if (userToken == null || userToken.isExpired()) {
            if (userToken != null) {
                tokens.remove(token);
            }
            return new TokenValidationResult(false, null, "Invalid or expired token");
        }
        return new TokenValidationResult(true, userToken.userId, null);
    }

    private static class UserToken {
        final long userId;
        final String username;
        final Instant expiresAt;

        UserToken(long userId, String username, Instant expiresAt) {
            this.userId = userId;
            this.username = username;
            this.expiresAt = expiresAt;
        }

        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    public record TokenValidationResult(boolean valid, Long userId, String error) {}
}
