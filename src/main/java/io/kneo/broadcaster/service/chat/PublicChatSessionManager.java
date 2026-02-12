package io.kneo.broadcaster.service.chat;

import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.HTreeMap;
import org.mapdb.Serializer;

import java.io.File;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class PublicChatSessionManager {
    private static final long CODE_EXPIRY_SECONDS = 300;
    private static final long SESSION_EXPIRY_SECONDS = 86400;  //24 hours
    private static final int MAX_ATTEMPTS = 3;

    private final Map<String, VerificationCode> verificationCodes = new ConcurrentHashMap<>();
    private final SecureRandom secureRandom = new SecureRandom();
    private DB db;
    private HTreeMap<String, PublicChatSession> sessions;

    @SuppressWarnings("unchecked")
    @PostConstruct
    void init() {
        new File("sessions_data").mkdirs();

        this.db = DBMaker
                .fileDB("sessions_data/chat-sessions.db")
                .transactionEnable()
                .make();

        this.sessions = db
                .hashMap("sessions", Serializer.STRING, Serializer.JAVA)
                .expireAfterCreate(SESSION_EXPIRY_SECONDS, TimeUnit.SECONDS)
                .createOrOpen();
    }

    @PreDestroy
    void shutdown() {
        db.close();
    }

    @Scheduled(every = "1h")
    void cleanupExpiredSessions() {
        sessions.entrySet().removeIf(entry -> entry.getValue().isExpired());
        db.commit();
    }

    @Scheduled(every = "30s")
    void debugSessions() {
        System.out.println("=== MapDB Sessions Debug ===");
        System.out.println("Total sessions: " + sessions.size());
        sessions.forEach((token, session) -> {
            System.out.println("Token: " + token);
            System.out.println("  Email: " + session.email());
            System.out.println("  Expires: " + session.expiresAt());
            System.out.println("  Expired: " + session.isExpired());
        });
        System.out.println("===========================");
    }

    public String generateAndStoreCode(String email) {
        String code = String.valueOf(1000 + secureRandom.nextInt(9000));
        verificationCodes.put(email.toLowerCase(), new VerificationCode(code, Instant.now().plusSeconds(CODE_EXPIRY_SECONDS)));
        return code;
    }

    public VerificationResult verifyCode(String code, String email) {
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
        // Remove existing sessions for this email
        sessions.entrySet().removeIf(entry -> entry.getValue().email().equals(email));

        String token = UUID.randomUUID().toString();
        sessions.put(token, new PublicChatSession(email, Instant.now().plusSeconds(SESSION_EXPIRY_SECONDS)));
        db.commit();
        return token;
    }

    public String validateSessionAndGetEmail(String token) {
        PublicChatSession session = sessions.get(token);
        return session != null ? session.email() : null;
    }

    public void storeUserToken(String userToken, String email) {
        sessions.put(userToken, new PublicChatSession(email, Instant.now().plusSeconds(SESSION_EXPIRY_SECONDS)));
        db.commit();
    }

    private void cleanupExpiredCodes() {
        verificationCodes.entrySet().removeIf(entry -> entry.getValue().isExpired());
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
