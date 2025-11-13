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
public class StatsAccumulator implements IStatsService {
    private static final Logger LOGGER = LoggerFactory.getLogger(StatsAccumulator.class);

    private final ConcurrentHashMap<String, AtomicLong> accessCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> lastUserAgents = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> lastIpAddresses = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> lastCountryCodes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, OffsetDateTime> lastAccessTimes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, OffsetDateTime>> activeListeners = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, ConcurrentHashMap<String, Boolean>>> countryListeners = new ConcurrentHashMap<>();

    @Inject
    RadioStationRepository radioStationRepository;

    public void recordAccess(String stationName, String userAgent, String ipAddress, String countryCode) {
        OffsetDateTime now = OffsetDateTime.now();
        
        accessCounts.computeIfAbsent(stationName, k -> new AtomicLong(0)).incrementAndGet();
        lastUserAgents.put(stationName, userAgent);
        lastIpAddresses.put(stationName, ipAddress);
        lastCountryCodes.put(stationName, countryCode);
        lastAccessTimes.put(stationName, now);

        activeListeners.computeIfAbsent(stationName, k -> new ConcurrentHashMap<>())
                .put(ipAddress, now);

        if (!"UNKNOWN".equals(countryCode)) {
            countryListeners.computeIfAbsent(stationName, k -> new ConcurrentHashMap<>())
                    .computeIfAbsent(countryCode, k -> new ConcurrentHashMap<>())
                    .put(ipAddress, Boolean.TRUE);
        }

        LOGGER.debug("Recorded access for station: {} from IP: {} ({}) (total pending: {})",
                stationName, ipAddress, countryCode, accessCounts.get(stationName).get());
    }

    public Uni<Void> flushAllStats() {
        if (accessCounts.isEmpty()) {
            LOGGER.debug("No stats to flush");
            return Uni.createFrom().voidItem();
        }

        Map<String, Long> countsSnapshot = new HashMap<>();
        Map<String, String> agentsSnapshot = new HashMap<>();
        Map<String, String> ipSnapshot = new HashMap<>();
        Map<String, String> countrySnapshot = new HashMap<>();
        Map<String, OffsetDateTime> timesSnapshot = new HashMap<>();

        accessCounts.forEach((station, count) -> {
            long currentCount = count.getAndSet(0);
            if (currentCount > 0) {
                countsSnapshot.put(station, currentCount);
                agentsSnapshot.put(station, lastUserAgents.get(station));
                ipSnapshot.put(station, lastIpAddresses.get(station));
                countrySnapshot.put(station, lastCountryCodes.get(station));
                timesSnapshot.put(station, lastAccessTimes.get(station));
            }
        });

        accessCounts.entrySet().removeIf(entry -> entry.getValue().get() == 0);
        countsSnapshot.keySet().forEach(station -> {
            lastUserAgents.remove(station);
            lastIpAddresses.remove(station);
            lastCountryCodes.remove(station);
            lastAccessTimes.remove(station);
        });
        // Note: countryStats is NOT cleared - it accumulates over time for dashboard display

        if (countsSnapshot.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        LOGGER.info("Flushing stats for {} stations to database", countsSnapshot.size());

        return Uni.join().all(
                        countsSnapshot.entrySet().stream()
                                .map(entry -> {
                                    String station = entry.getKey();
                                    Long count = entry.getValue();
                                    String userAgent = agentsSnapshot.get(station);
                                    String ipAddress = ipSnapshot.get(station);
                                    String countryCode = countrySnapshot.get(station);
                                    OffsetDateTime lastAccess = timesSnapshot.get(station);

                                    return flushStationStats(station, count, userAgent, ipAddress, countryCode, lastAccess);
                                })
                                .toList()
                ).andFailFast()
                .replaceWithVoid()
                .onFailure().invoke(failure -> {
                    LOGGER.error("Failed to flush stats batch, some data may be lost", failure);
                });
    }

    private Uni<Void> flushStationStats(String stationName, Long count, String userAgent, String ipAddress, String countryCode, OffsetDateTime lastAccess) {
        String dbCountryCode = "UNKNOWN".equals(countryCode) ? null : countryCode;

        return radioStationRepository.upsertStationAccessWithCountAndGeo(
                stationName,
                count,
                lastAccess,
                userAgent,
                ipAddress,
                dbCountryCode
        ).onFailure().invoke(failure ->
                LOGGER.error("Failed to flush stats for station: {}, lost {} access records",
                        stationName, count, failure)
        );
    }

    public int getPendingStatsCount() {
        return accessCounts.size();
    }

    public long getTotalPendingAccesses() {
        return accessCounts.values().stream()
                .mapToLong(AtomicLong::get)
                .sum();
    }

    public long getCurrentListeners(String stationName) {
        ConcurrentHashMap<String, OffsetDateTime> listeners = activeListeners.get(stationName);
        if (listeners == null || listeners.isEmpty()) {
            return 0;
        }
        
        // Consider a listener active if they accessed within the last 5 minutes
        OffsetDateTime threshold = OffsetDateTime.now().minusMinutes(5);
        
        // Clean up stale listeners and count active ones
        listeners.entrySet().removeIf(entry -> entry.getValue().isBefore(threshold));
        
        return listeners.size();
    }

    public Map<String, Long> getCountryStats(String stationName) {
        ConcurrentHashMap<String, ConcurrentHashMap<String, Boolean>> stationCountries = countryListeners.get(stationName);
        if (stationCountries == null || stationCountries.isEmpty()) {
            return Map.of();
        }
        
        Map<String, Long> result = new HashMap<>();
        stationCountries.forEach((country, ips) -> result.put(country, (long) ips.size()));
        return result;
    }

    public void clearAllCountryStats() {
        countryListeners.clear();
        LOGGER.info("Cleared all country stats");
    }
}