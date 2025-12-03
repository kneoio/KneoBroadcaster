package io.kneo.broadcaster.service.util;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

@ApplicationScoped
public class FileOperationLockService {
    private static final Logger LOGGER = LoggerFactory.getLogger(FileOperationLockService.class);

    private static final Duration LOCK_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration CLEANUP_INTERVAL = Duration.ofMinutes(10);

    private final Vertx vertx;
    private final ConcurrentHashMap<String, LockInfo> activeLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> lockObjects = new ConcurrentHashMap<>();

    @Inject
    public FileOperationLockService(Vertx vertx) {
        this.vertx = vertx;
        startLockCleanup();
    }

    public <T> Uni<T> withUserLock(String username, Supplier<Uni<T>> operation) {
        String lockKey = "user:" + username;
        return executeWithLock(lockKey, operation, "user operation for: " + username);
    }

    public <T> Uni<T> withEntityLock(String username, String entityId, Supplier<Uni<T>> operation) {
        String lockKey = "entity:" + username + ":" + entityId;
        return executeWithLock(lockKey, operation, "entity operation for: " + username + "/" + entityId);
    }

    public <T> Uni<T> withFileLock(String username, String entityId, String fileName, Supplier<Uni<T>> operation) {
        String lockKey = "file:" + username + ":" + entityId + ":" + fileName;
        return executeWithLock(lockKey, operation, "file operation for: " + username + "/" + entityId + "/" + fileName);
    }

    public <T> Uni<T> withTempLock(String username, Supplier<Uni<T>> operation) {
        String lockKey = "temp:" + username;
        return executeWithLock(lockKey, operation, "temp operation for: " + username);
    }

    public <T> Uni<T> withMultipleLocks(String[] lockKeys, Supplier<Uni<T>> operation, String operationDescription) {
        String[] sortedKeys = lockKeys.clone();
        java.util.Arrays.sort(sortedKeys);

        return acquireMultipleLocks(sortedKeys, 0, operation, operationDescription);
    }

    private <T> Uni<T> acquireMultipleLocks(String[] sortedKeys, int index,
                                            Supplier<Uni<T>> operation, String operationDescription) {
        if (index >= sortedKeys.length) {
            return operation.get()
                    .eventually(() -> releaseMultipleLocks(sortedKeys));
        }

        return executeWithLock(sortedKeys[index],
                () -> acquireMultipleLocks(sortedKeys, index + 1, operation, operationDescription),
                operationDescription);
    }

    private Uni<Void> releaseMultipleLocks(String[] lockKeys) {
        return Uni.createFrom().voidItem();
    }

    private <T> Uni<T> executeWithLock(String lockKey, Supplier<Uni<T>> operation, String operationDescription) {
        return Uni.createFrom().item(() -> {
                    ReentrantLock lock = lockObjects.computeIfAbsent(lockKey, k -> new ReentrantLock());
                    LOGGER.debug("Acquiring lock for: {} ({})", lockKey, operationDescription);

                    boolean acquired = false;
                    try {
                        acquired = lock.tryLock(LOCK_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Lock acquisition interrupted for: " + lockKey, e);
                    }

                    if (!acquired) {
                        throw new RuntimeException("Failed to acquire lock within timeout for: " + lockKey);
                    }

                    activeLocks.put(lockKey, new LockInfo(lockKey, LocalDateTime.now(), operationDescription));

                    return lock;
                })
                .onItem().transformToUni(lock -> {
                    LOGGER.debug("Lock acquired for: {} ({})", lockKey, operationDescription);

                    return operation.get()
                            .onTermination().invoke((result, failure, cancelled) -> {
                                try {
                                    lock.unlock();
                                    activeLocks.remove(lockKey);
                                    LOGGER.debug("Lock released for: {} ({})", lockKey, operationDescription);
                                } catch (Exception e) {
                                    LOGGER.error("Error releasing lock for: {}", lockKey, e);
                                }
                            });
                })
                .runSubscriptionOn(io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool());
    }

    public boolean isLocked(String lockKey) {
        return activeLocks.containsKey(lockKey);
    }

    public LockStats getLockStats() {
        return LockStats.builder()
                .activeLocks(activeLocks.size())
                .totalLockObjects(lockObjects.size())
                .build();
    }

    public java.util.Map<String, LockInfo> getActiveLocks() {
        return new ConcurrentHashMap<>(activeLocks);
    }

    private void startLockCleanup() {
        vertx.setPeriodic(CLEANUP_INTERVAL.toMillis(), timerId -> {
            cleanupStaleLocks();
        });
    }

    private void cleanupStaleLocks() {
        LocalDateTime cutoff = LocalDateTime.now().minus(LOCK_TIMEOUT);

        activeLocks.entrySet().removeIf(entry -> {
            LockInfo lockInfo = entry.getValue();
            if (lockInfo.getAcquiredAt().isBefore(cutoff)) {
                LOGGER.warn("Removing stale lock: {} acquired at: {}",
                        entry.getKey(), lockInfo.getAcquiredAt());

                ReentrantLock lock = lockObjects.get(entry.getKey());
                if (lock != null && lock.isHeldByCurrentThread()) {
                    try {
                        lock.unlock();
                    } catch (Exception e) {
                        LOGGER.error("Error force-unlocking stale lock: {}", entry.getKey(), e);
                    }
                }

                return true;
            }
            return false;
        });

        lockObjects.entrySet().removeIf(entry -> {
            ReentrantLock lock = entry.getValue();
            String key = entry.getKey();

            if (!activeLocks.containsKey(key) && !lock.hasQueuedThreads() && !lock.isLocked()) {
                LOGGER.debug("Removing unused lock object: {}", key);
                return true;
            }
            return false;
        });
    }

    @Getter
    public static class LockInfo {
        private final String lockKey;
        private final LocalDateTime acquiredAt;
        private final String operation;

        public LockInfo(String lockKey, LocalDateTime acquiredAt, String operation) {
            this.lockKey = lockKey;
            this.acquiredAt = acquiredAt;
            this.operation = operation;
        }

    }

    @Getter
    public static class LockStats {
        private final int activeLocks;
        private final int totalLockObjects;

        private LockStats(int activeLocks, int totalLockObjects) {
            this.activeLocks = activeLocks;
            this.totalLockObjects = totalLockObjects;
        }

        public static LockStatsBuilder builder() {
            return new LockStatsBuilder();
        }

        public static class LockStatsBuilder {
            private int activeLocks;
            private int totalLockObjects;

            public LockStatsBuilder activeLocks(int activeLocks) {
                this.activeLocks = activeLocks;
                return this;
            }

            public LockStatsBuilder totalLockObjects(int totalLockObjects) {
                this.totalLockObjects = totalLockObjects;
                return this;
            }

            public LockStats build() {
                return new LockStats(activeLocks, totalLockObjects);
            }
        }
    }
}