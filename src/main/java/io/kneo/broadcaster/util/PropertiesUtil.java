package io.kneo.broadcaster.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public final class PropertiesUtil {
    
    public static String getProperty(String fileName, String key) {
        Properties properties = new Properties();
        try (InputStream input = PropertiesUtil.class.getClassLoader().getResourceAsStream(fileName)) {
            if (input == null) {
                System.err.println("Unable to find " + fileName);
                return null;
            }
            properties.load(input);
            return properties.getProperty(key);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }
    
    public static String getDevProperty(String key) {
        return getProperty("application-dev.properties", key);
    }
}
