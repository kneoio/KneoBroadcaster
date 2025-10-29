package io.kneo.broadcaster.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kneo.core.localization.LanguageCode;
import lombok.Setter;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class NewsIntroDraft implements IDraft {
    private final String title;
    private final String artist;
    private final String aiDjName;
    private final String brand;
    private final List<Object> environment;
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

    public NewsIntroDraft(String title, String artist, String aiDjName, String brand,
                          List<Object> context, LanguageCode languageCode) {
        this.title = title;
        this.artist = artist;
        this.aiDjName = aiDjName;
        this.brand = brand;
        this.environment = context;
        this.messages = TRANSLATIONS.get(languageCode.name().toLowerCase());
    }

    private static Map<String, Map<String, String>> loadTranslations() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            InputStream inputStream = NewsIntroDraft.class.getClassLoader().getResourceAsStream("draftbuilder_messages.json");
            return mapper.readValue(inputStream, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Map<String, String>>>() {});
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
        } else {
            if (chance(djProbability)) {
                intro.append(messages.get("dj.persona")).append(aiDjName);
                added = true;
            }
            if (chance(brandProbability)) {
                if (added) intro.append("\n");
                intro.append(messages.get("station.brand")).append(brand);
            }
        }

        intro.append("\n").append(messages.get("now.playing")).append("\"").append(title)
                .append(messages.get("by")).append(artist);

        if (chance(atmosphereProbability)) {
            Object first = environment.get(0);
            String ctxText;
            if (first instanceof Map<?, ?> map) {
                ctxText = map.entrySet().stream()
                        .map(e -> e.getKey() + ": " + e.getValue())
                        .reduce((a, b) -> a + ", " + b).orElse("");
            } else {
                ctxText = environment.toString();
            }
            intro.append("\n").append(messages.get("atmosphere.hint")).append(ctxText);
        }
        return intro.toString();
    }
}
