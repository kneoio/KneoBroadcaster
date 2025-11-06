package io.kneo.broadcaster.agent;

import io.kneo.broadcaster.ai.NewsHelper;
import io.kneo.broadcaster.config.WorldNewsApiConfig;
import io.kneo.broadcaster.util.PropertiesUtil;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.client.HttpResponse;
import io.vertx.mutiny.ext.web.client.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class WorldNewsApiClient {

    @Inject
    WorldNewsApiConfig config;

    @Inject
    Vertx vertx;

    private WebClient webClient;

    @PostConstruct
    void init() {
        this.webClient = WebClient.create(vertx);
    }

    public Uni<JsonObject> searchNews(String text, String sourceCountry, String language, Integer number) {
        var request = webClient
                .getAbs(config.getBaseUrl() + "/search-news")
                .addQueryParam("api-key", config.getApiKey());
        
        if (text != null) request.addQueryParam("text", text);
        if (sourceCountry != null) request.addQueryParam("source-country", sourceCountry);
        if (language != null) request.addQueryParam("language", language);
        if (number != null) request.addQueryParam("number", String.valueOf(number));
        
        return request.send().map(HttpResponse::bodyAsJsonObject);
    }

    public static void main(String[] args) {
        String apiKey = PropertiesUtil.getDevProperty("worldnews.api.key");
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("ERROR: worldnews.api.key not found");
            return;
        }
        
        io.vertx.core.Vertx coreVertx = io.vertx.core.Vertx.vertx();
        Vertx vertx = new Vertx(coreVertx);
        
        WorldNewsApiClient apiClient = new WorldNewsApiClient();
        apiClient.webClient = WebClient.create(vertx);

        apiClient.config = new WorldNewsApiConfig() {
            @Override
            public String getApiKey() { return apiKey; }
            @Override
            public String getBaseUrl() { return "https://api.worldnewsapi.com"; }
        };
        
        NewsHelper newsHelper = new NewsHelper(apiClient, "pt", "pt");
        
        try {
            System.out.println("Headlines (3 items):");
            java.util.List<String> headlines = newsHelper.summaries("music", 3);
            for (int i = 0; i < headlines.size(); i++) {
                System.out.println((i + 1) + ". " + headlines.get(i));
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
        
        coreVertx.close();
    }
}
