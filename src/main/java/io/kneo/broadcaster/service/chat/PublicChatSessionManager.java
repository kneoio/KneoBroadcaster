package io.kneo.broadcaster.service.chat;

import jakarta.enterprise.context.ApplicationScoped;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class PublicChatSessionManager {
    private static final long CODE_EXPIRY_SECONDS = 300;
    private static final long SESSION_EXPIRY_SECONDS = 3600;
    private static final int MAX_ATTEMPTS = 3;
    
    private final Map<String, VerificationCode> verificationCodes = new ConcurrentHashMap<>();
    private final Map<String, PublicChatSession> sessions = new ConcurrentHashMap<>();
    private final SecureRandom secureRandom = new SecureRandom();

    public String generateAndStoreCode(String email) {
        String code = String.valueOf(1000 + secureRandom.nextInt(9000));
        verificationCodes.put(email.toLowerCase(), new VerificationCode(code, Instant.now().plusSeconds(CODE_EXPIRY_SECONDS)));
        return code;
    }

    public VerificationResult verifyCode(String email, String code) {
        cleanupExpiredCodes();
        
        String normalizedEmail = email.toLowerCase();
        VerificationCode storedCode = verificationCodes.get(normalizedEmail);
        
        if (storedCode == null) {
            return new VerificationResult(false, "No verification code found for this email", null);
        }
        
        if (storedCode.isExpired()) {
            verificationCodes.remove(normalizedEmail);
            return new VerificationResult(false, "Verification code has expired", null);
        }
        
        if (storedCode.attempts >= MAX_ATTEMPTS) {
            verificationCodes.remove(normalizedEmail);
            return new VerificationResult(false, "Too many failed attempts", null);
        }
        
        if (!storedCode.code.equals(code)) {
            storedCode.attempts++;
            return new VerificationResult(false, "Invalid verification code", null);
        }
        
        verificationCodes.remove(normalizedEmail);
        String sessionToken = createSession(normalizedEmail);
        return new VerificationResult(true, "Verification successful", sessionToken);
    }

    private String createSession(String email) {
        String token = UUID.randomUUID().toString();
        sessions.put(token, new PublicChatSession(email, Instant.now().plusSeconds(SESSION_EXPIRY_SECONDS)));
        return token;
    }

    public String validateSessionAndGetEmail(String token) {
        cleanupExpiredSessions();
        
        PublicChatSession session = sessions.get(token);
        if (session == null || session.isExpired()) {
            if (session != null) {
                sessions.remove(token);
            }
            return null;
        }
        
        return session.email();
    }

    public void invalidateSession(String token) {
        sessions.remove(token);
    }

    private void cleanupExpiredCodes() {
        verificationCodes.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }

    private void cleanupExpiredSessions() {
        sessions.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }

    private static class VerificationCode {
        final String code;
        final Instant expiresAt;
        int attempts = 0;

        VerificationCode(String code, Instant expiresAt) {
            this.code = code;
            this.expiresAt = expiresAt;
        }

        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    public record VerificationResult(boolean success, String message, String sessionToken) {}
}
