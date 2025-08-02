package io.kneo.broadcaster.service;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class GeolocationService {
    private final ConcurrentHashMap<String, String> countryCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> cacheTimestamps = new ConcurrentHashMap<>();
    private static final long CACHE_DURATION = 24 * 60 * 60 * 1000; // 24 hours

    public Uni<String> getCountryAsync(String ip) {
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