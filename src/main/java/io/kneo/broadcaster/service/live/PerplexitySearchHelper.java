package io.kneo.broadcaster.service.live;

import io.kneo.broadcaster.agent.PerplexityApiClient;
import io.kneo.core.localization.LanguageCode;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

@ApplicationScoped
public class PerplexitySearchHelper {
    
    @Inject
    PerplexityApiClient perplexityApiClient;
    
    public PerplexitySearchHelper(PerplexityApiClient perplexityApiClient) {
        this.perplexityApiClient = perplexityApiClient;
    }
    
    public Uni<JsonObject> search(String query, List<LanguageCode> languages, List<String> domains) {
        return perplexityApiClient.search(query, languages, domains)
                .onFailure().recoverWithItem(e -> 
                    new JsonObject().put("error", "Search failed: " + e.getMessage())
                );
    }
    
    public Uni<JsonObject> search(String query) {
        return search(query, List.of(), List.of());
    }
}
