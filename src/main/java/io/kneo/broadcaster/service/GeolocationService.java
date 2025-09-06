package io.kneo.broadcaster.service;

import io.kneo.broadcaster.service.stats.StatsAccumulator;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class GeolocationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(GeolocationService.class);
    private final ConcurrentHashMap<String, String> countryCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> cacheTimestamps = new ConcurrentHashMap<>();
    private static final long CACHE_DURATION = 24 * 60 * 60 * 1000; // 24 hours

    @Inject
    StatsAccumulator statsAccumulator;

    public Uni<String> persistCountryAsync(String ip) {
        if (ip == null || ip.isEmpty()) {
            return Uni.createFrom().item("UNKNOWN");
        }

        String cached = getCachedCountry(ip);
        if (cached != null) {
            return Uni.createFrom().item(cached);
        }

        return Uni.createFrom().completionStage(() ->
                CompletableFuture.supplyAsync(() -> lookupCountry(ip))
        ).onItem().invoke(country -> putInCache(ip, country));
    }

    public Uni<String> recordAccessWithGeolocation(String brand, String userAgent, String clientIPHeader) {
        String[] ipCountryParts = parseIPHeader(clientIPHeader);
        String clientIP = ipCountryParts[0];
        String headerCountry = ipCountryParts[1];

        if (headerCountry != null) {
            // Use country from header if available
            try {
                statsAccumulator.recordAccess(brand, userAgent, clientIP, headerCountry);
                LOGGER.debug("Recorded access for brand: {} from IP: {} ({})", brand, clientIP, headerCountry);
                return Uni.createFrom().item(headerCountry);
            } catch (Exception e) {
                LOGGER.error("Failed to record access for brand: {}, IP: {}", brand, clientIP, e);
                return Uni.createFrom().item("ERROR");
            }
        }

        // Fallback to geolocation lookup
        return persistCountryAsync(clientIP)
                .onItem().invoke(country -> {
                    try {
                        statsAccumulator.recordAccess(brand, userAgent, clientIP, country);
                        LOGGER.debug("Recorded access for brand: {} from IP: {} ({})", brand, clientIP, country);
                    } catch (Exception e) {
                        LOGGER.error("Failed to record access for brand: {}, IP: {}", brand, clientIP, e);
                    }
                })
                .onFailure().invoke(failure ->
                        LOGGER.warn("Failed to get country for IP: {}, recording access without geo data", clientIP, failure)
                )
                .onFailure().recoverWithItem(() -> {
                    try {
                        statsAccumulator.recordAccess(brand, userAgent, clientIP, "UNKNOWN");
                        return "UNKNOWN";
                    } catch (Exception e) {
                        LOGGER.error("Failed to record fallback access for brand: {}", brand, e);
                        return "ERROR";
                    }
                });
    }

    private String[] parseIPHeader(String headerValue) {
        if (headerValue == null || headerValue.isEmpty()) {
            return new String[]{null, null};
        }

        if (headerValue.contains("|")) {
            String[] parts = headerValue.split("\\|", 2);
            return new String[]{parts[0].trim(), parts[1].trim()};
        }

        // No country code in header, just IP
        return new String[]{headerValue.trim(), null};
    }

    private String getCachedCountry(String ip) {
        Long timestamp = cacheTimestamps.get(ip);
        if (timestamp != null && System.currentTimeMillis() - timestamp < CACHE_DURATION) {
            return countryCache.get(ip);
        }
        return null;
    }

    private void putInCache(String ip, String country) {
        countryCache.put(ip, country);
        cacheTimestamps.put(ip, System.currentTimeMillis());
    }

    private String lookupCountry(String ip) {
        return "PT";
    }
}