package io.kneo.broadcaster.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public class BrandActivityLogger {
    private static final Logger LOGGER = LoggerFactory.getLogger(BrandActivityLogger.class);
    private static final String BRAND_KEY = "brand";
    private static final String ACTION_KEY = "action";

    private BrandActivityLogger() {
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
