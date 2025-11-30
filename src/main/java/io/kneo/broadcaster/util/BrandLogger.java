package io.kneo.broadcaster.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public class BrandLogger {
    private static final Logger LOGGER = LoggerFactory.getLogger(BrandLogger.class);
    private static final String BRAND_KEY = "brand";
    private static final String ACTION_KEY = "action";

    private BrandLogger() {
    }

    public static void logActivity(String brandName, String action, String message, Object... args) {
        try {
            MDC.put(BRAND_KEY, brandName);
            MDC.put(ACTION_KEY, action);
            
            String formattedMessage = String.format(message, args);
            
            LOGGER.info("BRAND_ACTIVITY - {} - {} - {}", brandName, action, formattedMessage);
        } finally {
            MDC.clear();
        }
    }
}
