package io.kneo.broadcaster.dto;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.time.Duration;

public class DurationSerializer extends JsonSerializer<Duration> {
    
    @Override
    public void serialize(Duration duration, JsonGenerator gen, SerializerProvider provider) throws IOException {
        if (duration == null) {
            gen.writeNull();
            return;
        }
        
        // Convert Duration to MM:SS format for frontend (total minutes and seconds)
        long totalMinutes = duration.toMinutes();
        long seconds = duration.toSecondsPart();
        
        gen.writeString(String.format("%d:%02d", totalMinutes, seconds));
    }
}
