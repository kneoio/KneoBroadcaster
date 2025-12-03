package io.kneo.broadcaster.service.util;

import io.kneo.broadcaster.service.stats.StatsAccumulator;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class GeolocationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(GeolocationService.class);

    @Inject
    StatsAccumulator statsAccumulator;

    public Uni<String> recordAccessWithGeolocation(String brand, String userAgent, String clientIPHeader) {
        String[] ipCountryParts = parseIPHeader(clientIPHeader);
        String clientIP = ipCountryParts[0];
        String country = ipCountryParts[1];

        try {
            statsAccumulator.recordAccess(brand, userAgent, clientIP, country);
            LOGGER.debug("Recorded access for brand: {} from IP: {} ({})", brand, clientIP, country);
            return Uni.createFrom().item(country);
        } catch (Exception e) {
            LOGGER.error("Failed to record access for brand: {}, IP: {}", brand, clientIP, e);
            return Uni.createFrom().item("ERROR");
        }
    }

    public static String[] parseIPHeader(String headerValue) {
        //headerValue = "192.168.1.100|US";
        //LOGGER.info("HEADER VALUE {}", headerValue);
        if (headerValue == null || headerValue.isEmpty()) {
            return new String[]{"UNKNOWN", "UNKNOWN"};
        }

        String[] parts = headerValue.split("\\|", 2);
        if (parts.length == 2) {
            String ip = parts[0].trim();
            String country = parts[1].trim();
            return new String[]{ip, country.isEmpty() ? "UNKNOWN" : country};
        }

        // No country code in header, just IP
        String ip = headerValue.trim();
        return new String[]{ip.isEmpty() ? "UNKNOWN" : ip, "UNKNOWN"};
    }
}