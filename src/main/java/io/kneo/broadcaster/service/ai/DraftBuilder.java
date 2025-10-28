package io.kneo.broadcaster.service.ai;

import java.util.*;

public class DraftBuilder {

    public static String buildAdIntroText(String title, String artist) {
        return "\nAdvertisement: Break — \"" + title + "\" by " + artist;
    }

    public static String buildDraft(
            String title,
            String artist,
            String aiDjName,
            String brand,
            String songDescription,
            List<String> genres,
            List<Map<String, Object>> history,
            List<Object> context,
            double djProbability,
            double brandProbability,
            double combinedProbability,
            double atmosphereProbability
    ) {
        Random random = new Random();
        StringBuilder introText = new StringBuilder();
        boolean added = false;

        if (random.nextDouble() < combinedProbability) {
            introText.append("DJ Persona: ").append(aiDjName)
                    .append("\nStation Brand: ").append(brand);
            added = true;
        } else {
            if (random.nextDouble() < djProbability) {
                introText.append("DJ Persona: ").append(aiDjName);
                added = true;
            }
            if (random.nextDouble() < brandProbability) {
                if (added) introText.append("\n");
                introText.append("Station Brand: ").append(brand);
                added = true;
            }
        }

        introText.append("\nNow playing: \"").append(title).append("\" by ").append(artist);

        if (songDescription != null && !songDescription.isEmpty()) {
            introText.append("\nDescription: ").append(songDescription);
        }
        if (genres != null && !genres.isEmpty()) {
            introText.append("\nGenres: ").append(String.join(", ", genres));
        }
        if (history != null && !history.isEmpty()) {
            Map<String, Object> prev = history.get(history.size() - 1);
            introText.append("\nHistory: Played \"").append(prev.get("title"))
                    .append("\" by ").append(prev.get("artist")).append(".");
            Object intro = prev.get("introSpeech");
            if (intro != null && !intro.toString().isEmpty()) {
                introText.append(" Last intro speech was: ").append(intro);
            }
        }
        if (context != null && !context.isEmpty() && random.nextDouble() < atmosphereProbability) {
            String ctxText;
            Object first = context.get(0);
            if (context.size() == 1 && first instanceof Map) {
                Map<?, ?> ctxMap = (Map<?, ?>) first;
                List<String> ctxLines = new ArrayList<>();
                for (Map.Entry<?, ?> entry : ctxMap.entrySet()) {
                    if (entry.getValue() != null && !entry.getValue().toString().isEmpty()) {
                        ctxLines.add(entry.getKey() + ": " + entry.getValue());
                    }
                }
                ctxText = String.join(", ", ctxLines);
            } else {
                ctxText = context.toString();
            }
            introText.append("\nAtmosphere hint: ").append(ctxText);
        }

        return introText.toString();
    }
}
