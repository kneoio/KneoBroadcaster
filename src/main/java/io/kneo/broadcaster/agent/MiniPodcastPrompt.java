package io.kneo.broadcaster.agent;

import java.util.List;

public class MiniPodcastPrompt {    
    private final String title;
    private final String artist;
    private final String genre;
    private final String description;
    private final String djName;
    private final String brand;
    private final String previousTrack;
    private final String context;
    private final String history;
    
    public MiniPodcastPrompt(String title, String artist, String genre, String description, String djName, String brand, String previousTrack, String context, String history) {
        this.title = title;
        this.artist = artist;
        this.genre = genre;
        this.description = description;
        this.djName = djName;
        this.brand = brand;
        this.previousTrack = previousTrack;
        this.context = context;
        this.history = history;
    }
    
    public List<String> getMemberNames() {
        return List.of("title", "artist", "genre", "description", "djName", "brand", "previousTrack", "context", "history");
    }
    
    public String buildPrompt(String prompt) {
        return prompt
            .replace("{{title}}", title != null ? title : "")
            .replace("{{artist}}", artist != null ? artist : "")
            .replace("{{genre}}", genre != null ? genre : "")
            .replace("{{description}}", description != null ? description : "")
            .replace("{{djName}}", djName != null ? djName : "")
            .replace("{{brand}}", brand != null ? brand : "")
            .replace("{{previousTrack}}", previousTrack != null ? previousTrack : "")
            .replace("{{context}}", context != null ? context : "")
            .replace("{{history}}", history != null ? history : "");
    }
}
