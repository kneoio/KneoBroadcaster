package io.kneo.broadcaster.service.live;

import io.kneo.broadcaster.agent.PerplexityApiClient;
import io.kneo.core.localization.LanguageCode;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@ApplicationScoped
public class PerpelxitySearchHelper {
    
    @Inject
    PerplexityApiClient perplexityApiClient;
    
    public PerpelxitySearchHelper(PerplexityApiClient perplexityApiClient) {
        this.perplexityApiClient = perplexityApiClient;
    }
    
    public JsonObject search(String query, List<LanguageCode> languages, List<String> domains) {
        try {
            CompletableFuture<JsonObject> future = new CompletableFuture<>();
            
            perplexityApiClient.search(query, languages, domains)
                    .subscribe().with(
                            future::complete,
                            future::completeExceptionally
                    );
            
            return future.get();
        } catch (Exception e) {
            return new JsonObject()
                    .put("error", "Search failed: " + e.getMessage());
        }
    }
    
    public JsonObject search(String query) {
        return search(query, List.of(), List.of());
    }
}
