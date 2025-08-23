package io.kneo.broadcaster.service.stats;

import io.kneo.broadcaster.repository.RadioStationRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@ApplicationScoped
public class StatsAccumulator {
    private static final Logger LOGGER = LoggerFactory.getLogger(StatsAccumulator.class);

    private final ConcurrentHashMap<String, AtomicLong> accessCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> lastUserAgents = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, OffsetDateTime> lastAccessTimes = new ConcurrentHashMap<>();

    @Inject
    RadioStationRepository radioStationRepository;

    /**
     * Records an access in memory for later batch processing
     */
    public void recordAccess(String stationName, String userAgent) {
        accessCounts.computeIfAbsent(stationName, k -> new AtomicLong(0)).incrementAndGet();
        lastUserAgents.put(stationName, userAgent);
        lastAccessTimes.put(stationName, OffsetDateTime.now());

        LOGGER.debug("Recorded access for station: {} (total pending: {})",
                stationName, accessCounts.get(stationName).get());
    }

    /**
     * Gets current accumulated stats for a station without flushing
     */
    public StationStats getAccumulatedStats(String stationName) {
        AtomicLong count = accessCounts.get(stationName);
        String userAgent = lastUserAgents.get(stationName);
        OffsetDateTime lastAccess = lastAccessTimes.get(stationName);

        return new StationStats(
                count != null ? count.get() : 0,
                userAgent,
                lastAccess
        );
    }

    /**
     * Flushes all accumulated stats to database and clears memory
     */
    public Uni<Void> flushAllStats() {
        if (accessCounts.isEmpty()) {
            LOGGER.debug("No stats to flush");
            return Uni.createFrom().voidItem();
        }

        // Create snapshot and clear immediately to avoid blocking new requests
        Map<String, Long> countsSnapshot = new HashMap<>();
        Map<String, String> agentsSnapshot = new HashMap<>();
        Map<String, OffsetDateTime> timesSnapshot = new HashMap<>();

        // Atomic snapshot creation
        accessCounts.forEach((station, count) -> {
            long currentCount = count.getAndSet(0);
            if (currentCount > 0) {
                countsSnapshot.put(station, currentCount);
                agentsSnapshot.put(station, lastUserAgents.get(station));
                timesSnapshot.put(station, lastAccessTimes.get(station));
            }
        });

        // Clean up entries that were zeroed out
        accessCounts.entrySet().removeIf(entry -> entry.getValue().get() == 0);
        countsSnapshot.keySet().forEach(station -> {
            lastUserAgents.remove(station);
            lastAccessTimes.remove(station);
        });

        if (countsSnapshot.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        LOGGER.info("Flushing stats for {} stations to database", countsSnapshot.size());

        // Process each station's stats
        return Uni.join().all(
                        countsSnapshot.entrySet().stream()
                                .map(entry -> {
                                    String station = entry.getKey();
                                    Long count = entry.getValue();
                                    String userAgent = agentsSnapshot.get(station);
                                    OffsetDateTime lastAccess = timesSnapshot.get(station);

                                    return flushStationStats(station, count, userAgent, lastAccess);
                                })
                                .toList()
                ).andFailFast()
                .replaceWithVoid()
                .onFailure().invoke(failure -> {
                    LOGGER.error("Failed to flush stats batch, some data may be lost", failure);
                    // On failure, we could optionally restore the counts, but for MVP we'll accept the loss
                });
    }

    private Uni<Void> flushStationStats(String stationName, Long count, String userAgent, OffsetDateTime lastAccess) {
        return radioStationRepository.findStationStatsByStationName(stationName)
                .chain(existingStats -> {
                    if (existingStats != null) {
                        return radioStationRepository.upsertStationAccessWithCount(
                                stationName,
                                existingStats.getAccessCount() + count,
                                lastAccess,
                                userAgent
                        );
                    } else {
                        // Create new stats entry
                        return radioStationRepository.upsertStationAccessWithCount(
                                stationName,
                                count,
                                lastAccess,
                                userAgent
                        );
                    }
                })
                .onFailure().invoke(failure ->
                        LOGGER.error("Failed to flush stats for station: {}, lost {} access records",
                                stationName, count, failure)
                );
    }

    public int getPendingStatsCount() {
        return accessCounts.size();
    }

    /**
     * Gets total pending access count across all stations
     */
    public long getTotalPendingAccesses() {
        return accessCounts.values().stream()
                .mapToLong(AtomicLong::get)
                .sum();
    }


    public record StationStats(long accessCount, String lastUserAgent, OffsetDateTime lastAccessTime) {

    }
}