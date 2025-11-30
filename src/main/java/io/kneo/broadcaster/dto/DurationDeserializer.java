package io.kneo.broadcaster.dto;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.time.Duration;
import java.time.format.DateTimeParseException;

public class DurationDeserializer extends JsonDeserializer<Duration> {
    
    @Override
    public Duration deserialize(JsonParser p, DeserializationContext ctxt) throws IOException, JacksonException {
        String value = p.getValueAsString();
        
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        
        try {
            // Try MM:SS or HH:MM:SS format first (frontend format)
            if (value.contains(":") && !value.startsWith("PT")) {
                String[] parts = value.split(":");
                if (parts.length == 2) {
                    // MM:SS or MM:SS.mmm format
                    long minutes = Long.parseLong(parts[0]);
                    String secondsPart = parts[1];
                    if (secondsPart.contains(".")) {
                        String[] secAndMillis = secondsPart.split("\\.");
                        long seconds = Long.parseLong(secAndMillis[0]);
                        long millis = (long) (Double.parseDouble("0." + secAndMillis[1]) * 1000);
                        return Duration.ofMinutes(minutes).plusSeconds(seconds).plusMillis(millis);
                    } else {
                        long seconds = Long.parseLong(secondsPart);
                        return Duration.ofMinutes(minutes).plusSeconds(seconds);
                    }
                } else if (parts.length == 3) {
                    // HH:MM:SS or HH:MM:SS.mmm format
                    long hours = Long.parseLong(parts[0]);
                    long minutes = Long.parseLong(parts[1]);
                    String secondsPart = parts[2];
                    if (secondsPart.contains(".")) {
                        String[] secAndMillis = secondsPart.split("\\.");
                        long seconds = Long.parseLong(secAndMillis[0]);
                        long millis = (long) (Double.parseDouble("0." + secAndMillis[1]) * 1000);
                        return Duration.ofHours(hours).plusMinutes(minutes).plusSeconds(seconds).plusMillis(millis);
                    } else {
                        long seconds = Long.parseLong(secondsPart);
                        return Duration.ofHours(hours).plusMinutes(minutes).plusSeconds(seconds);
                    }
                }
            }
            
            // Try ISO-8601 duration format (backend format)
            return Duration.parse(value);
            
        } catch (NumberFormatException | DateTimeParseException e) {
            throw new IOException("Cannot parse duration from value: " + value, e);
        }
    }
}
