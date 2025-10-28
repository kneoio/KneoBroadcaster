package io.kneo.broadcaster.model.ai;

import lombok.Setter;

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

    @Setter
    private double djProbability = 0.3;
    @Setter
    private double brandProbability = 0.4;
    @Setter
    private double combinedProbability = 0.5;
    @Setter
    private double atmosphereProbability = 0.7;

    private final Random random = new Random();

    public DraftBuilder(String title, String artist, List<String> genres, String songDescription, String aiDjName, String brand,
                        List<Map<String, Object>> history, List<Object> context) {
        this.title = title;
        this.artist = artist;
        this.aiDjName = aiDjName;
        this.brand = brand;
        this.songDescription = songDescription;
        this.genres = genres;
        this.history = history;
        this.context = context;
    }

    private boolean chance(double probability) {
        return random.nextDouble() < probability;
    }

    public String build() {
        StringBuilder intro = new StringBuilder();
        boolean added = false;

        if (chance(combinedProbability)) {
            intro.append("DJ Persona: ").append(aiDjName)
                    .append("\nStation Brand: ").append(brand);
            added = true;
        } else {
            if (chance(djProbability)) {
                intro.append("DJ Persona: ").append(aiDjName);
                added = true;
            }
            if (chance(brandProbability)) {
                if (added) intro.append("\n");
                intro.append("Station Brand: ").append(brand);
                added = true;
            }
        }

        intro.append("\nNow playing: \"").append(title)
                .append("\" by ").append(artist);

        if (songDescription != null && !songDescription.isBlank()) {
            intro.append("\nDescription: ").append(songDescription);
        }
        if (genres != null && !genres.isEmpty()) {
            intro.append("\nGenres: ").append(String.join(", ", genres));
        }
        if (history != null && !history.isEmpty()) {
            Map<String, Object> prev = history.get(history.size() - 1);
            intro.append("\nHistory: Played \"").append(prev.get("title"))
                    .append("\" by ").append(prev.get("artist")).append(".");
            Object introSpeech = prev.get("introSpeech");
            if (introSpeech != null) {
                intro.append(" Last intro speech was: ").append(introSpeech);
            }
        }
        if (context != null && !context.isEmpty() && chance(atmosphereProbability)) {
            Object first = context.get(0);
            String ctxText;
            if (first instanceof Map<?, ?> map) {
                ctxText = map.entrySet().stream()
                        .filter(e -> e.getValue() != null)
                        .map(e -> e.getKey() + ": " + e.getValue())
                        .reduce((a, b) -> a + ", " + b).orElse("");
            } else {
                ctxText = context.toString();
            }
            intro.append("\nAtmosphere hint: ").append(ctxText);
        }
        return intro.toString();
    }
}
