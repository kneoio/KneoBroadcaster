package io.kneo.broadcaster.model.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kneo.core.localization.LanguageCode;
import lombok.Setter;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class DraftBuilder {
    private final String title;
    private final String artist;
    private final String aiDjName;
    private final String brand;
    private final String songDescription;
    private final List<String> genres;
    private final List<Map<String, Object>> history;
    private final List<Object> context;
    private final LanguageCode languageCode;
    private final Map<String, String> messages;

    @Setter
    private double djProbability = 0.3;
    @Setter
    private double brandProbability = 0.4;
    @Setter
    private double combinedProbability = 0.5;
    @Setter
    private double atmosphereProbability = 0.7;

    private final Random random = new Random();
    private static final Map<String, Map<String, String>> TRANSLATIONS = loadTranslations();

    public DraftBuilder(String title, String artist, List<String> genres, String songDescription, String aiDjName, String brand,
                        List<Map<String, Object>> history, List<Object> context, LanguageCode languageCode) {
        this.title = title;
        this.artist = artist;
        this.aiDjName = aiDjName;
        this.brand = brand;
        this.songDescription = songDescription;
        this.genres = genres;
        this.history = history;
        this.context = context;
        this.languageCode = languageCode;
        this.messages = TRANSLATIONS.get(languageCode.name().toLowerCase());
    }

    private static Map<String, Map<String, String>> loadTranslations() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            InputStream inputStream = DraftBuilder.class.getClassLoader().getResourceAsStream("draftbuilder_messages.json");
            return mapper.readValue(inputStream, mapper.getTypeFactory().constructMapType(HashMap.class, String.class, 
                    mapper.getTypeFactory().constructMapType(HashMap.class, String.class, String.class)));
        } catch (Exception e) {
            throw new RuntimeException("Failed to load translations", e);
        }
    }

    private boolean chance(double probability) {
        return random.nextDouble() < probability;
    }

    public String build() {
        StringBuilder intro = new StringBuilder();
        boolean added = false;

        if (chance(combinedProbability)) {
            intro.append(messages.get("dj.persona")).append(aiDjName)
                    .append("\n").append(messages.get("station.brand")).append(brand);
            added = true;
        } else {
            if (chance(djProbability)) {
                intro.append(messages.get("dj.persona")).append(aiDjName);
                added = true;
            }
            if (chance(brandProbability)) {
                if (added) intro.append("\n");
                intro.append(messages.get("station.brand")).append(brand);
                added = true;
            }
        }

        intro.append("\n").append(messages.get("now.playing")).append("\"").append(title)
                .append(messages.get("by")).append(artist);

        intro.append("\n").append(messages.get("description")).append(songDescription);
        intro.append("\n").append(messages.get("genres")).append(String.join(", ", genres));
        
        Map<String, Object> prev = history.get(history.size() - 1);
        intro.append("\n").append(messages.get("history.played")).append("\"").append(prev.get("title"))
                .append(messages.get("by")).append(prev.get("artist")).append(".");
        Object introSpeech = prev.get("introSpeech");
        intro.append(messages.get("last.intro.speech")).append(introSpeech);
        
        if (chance(atmosphereProbability)) {
            Object first = context.get(0);
            String ctxText;
            if (first instanceof Map<?, ?> map) {
                ctxText = map.entrySet().stream()
                        .map(e -> e.getKey() + ": " + e.getValue())
                        .reduce((a, b) -> a + ", " + b).orElse("");
            } else {
                ctxText = context.toString();
            }
            intro.append("\n").append(messages.get("atmosphere.hint")).append(ctxText);
        }
        return intro.toString();
    }
}
