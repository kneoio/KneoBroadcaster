package io.kneo.broadcaster.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Map;

public class BrandActivityLogger {
    private static final Logger LOGGER = LoggerFactory.getLogger(BrandActivityLogger.class);
    private static final String BRAND_KEY = "brand";
    private static final String ACTION_KEY = "action";
    private static final String USER_KEY = "user";
    
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

    public static void logActivityWithUser(String brandName, String action, String username, String message, Object... args) {
        try {
            MDC.put(BRAND_KEY, brandName);
            MDC.put(ACTION_KEY, action);
            MDC.put(USER_KEY, username);
            String formattedMessage = String.format(message, args);
            LOGGER.info("BRAND_ACTIVITY - {} - {} - {} - {}", 
                brandName, action, username, formattedMessage);
        } finally {
            MDC.clear();
        }
    }
    
    public static void logActivityWithContext(String brandName, String action, 
                                           Map<String, String> context, 
                                           String message, Object... args) {
        try {
            MDC.put(BRAND_KEY, brandName);
            MDC.put(ACTION_KEY, action);
            
            if (context != null) {
                context.forEach(MDC::put);
            }
            
            String formattedMessage = String.format(message, args);
            
            LOGGER.info("BRAND_ACTIVITY - {} - {} - {} - {}", 
                brandName, action, context != null ? context : "", formattedMessage);
        } finally {
            MDC.clear();
        }
    }
}
