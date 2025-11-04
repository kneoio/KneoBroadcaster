package io.kneo.broadcaster.ai;

import io.kneo.broadcaster.agent.WorldNewsApiClient;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class NewsHelper {
    private final WorldNewsApiClient client;
    private final String defaultCountry;
    private final String defaultLanguage;
    private final Map<String, CachedNews> cache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 15 * 60 * 1000; // 15 minutes

    public NewsHelper(WorldNewsApiClient client, String defaultCountry, String defaultLanguage) {
        this.client = client;
        this.defaultCountry = defaultCountry;
        this.defaultLanguage = defaultLanguage;
    }
    
    public JsonObject search(String text) {
        return searchWithCache(text, defaultCountry, defaultLanguage, 10);
    }

    public JsonObject search(String text, int number) {
        return searchWithCache(text, defaultCountry, defaultLanguage, number);
    }

    public JsonObject search(String text, String country, String language) {
        return searchWithCache(text, country, language, 10);
    }

    private JsonObject searchWithCache(String text, String country, String language, int number) {
        String cacheKey = text + "|" + country + "|" + language + "|" + number;
        
        CachedNews cached = cache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached.data;
        }
        
        try {
            JsonObject data = client.searchNews(text, country, language, number)
                    .await().indefinitely();
            
            cache.put(cacheKey, new CachedNews(data));
            return data;
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch news for: " + text, e);
        }
    }

    public List<String> headlines(String text, int number) {
        JsonObject result = search(text, number);
        List<String> headlines = new ArrayList<>();
        JsonArray news = result.getJsonArray("news");
        if (news != null) {
            for (int i = 0; i < news.size(); i++) {
                JsonObject article = news.getJsonObject(i);
                headlines.add(article.getString("title"));
            }
        }
        return headlines;
    }

    public List<String> headlines(String text, int number, String country, String language) {
        JsonObject result = search(text, country, language);
        List<String> headlines = new ArrayList<>();
        JsonArray news = result.getJsonArray("news");
        if (news != null) {
            int count = Math.min(number, news.size());
            for (int i = 0; i < count; i++) {
                JsonObject article = news.getJsonObject(i);
                headlines.add(article.getString("title"));
            }
        }
        return headlines;
    }

    public List<String> summaries(String text, int number) {
        JsonObject result = search(text, number);
        List<String> summaries = new ArrayList<>();
        JsonArray news = result.getJsonArray("news");
        if (news != null) {
            for (int i = 0; i < news.size(); i++) {
                JsonObject article = news.getJsonObject(i);
                String summary = article.getString("summary");
                if (summary != null && !summary.isEmpty()) {
                    summaries.add(summary);
                }
            }
        }
        return summaries;
    }

    public String brief(String text) {
        JsonObject result = search(text, 1);
        JsonArray news = result.getJsonArray("news");
        if (news != null && !news.isEmpty()) {
            JsonObject article = news.getJsonObject(0);
            String title = article.getString("title");
            String summary = article.getString("summary");
            return title + ". " + summary;
        }
        return "";
    }

    public List<JsonObject> articles(String text, int number) {
        JsonObject result = search(text, number);
        List<JsonObject> articles = new ArrayList<>();
        JsonArray news = result.getJsonArray("news");
        if (news != null) {
            for (int i = 0; i < news.size(); i++) {
                JsonObject article = news.getJsonObject(i);
                JsonObject simplified = new JsonObject()
                    .put("title", article.getString("title"))
                    .put("summary", article.getString("summary"))
                    .put("url", article.getString("url"))
                    .put("author", article.getString("author"))
                    .put("date", article.getString("publish_date"))
                    .put("sentiment", article.getDouble("sentiment"));
                articles.add(simplified);
            }
        }
        return articles;
    }

    private static class CachedNews {
        final JsonObject data;
        final long timestamp;

        CachedNews(JsonObject data) {
            this.data = data;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
    }
}
