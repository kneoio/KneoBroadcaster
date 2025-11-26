package io.kneo.broadcaster.util;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class ResourceUtil {

    private ResourceUtil() {}

    public static String loadResourceAsString(String resourcePath) {
        InputStream is = ResourceUtil.class.getResourceAsStream(resourcePath);
        if (is == null) {
            throw new IllegalStateException("Resource not found: " + resourcePath);
        }
        try (is) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            throw new RuntimeException("Failed to read resource: " + resourcePath, e);
        }
    }
}
