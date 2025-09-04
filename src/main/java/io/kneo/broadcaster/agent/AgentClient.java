package io.kneo.broadcaster.agent;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.client.WebClient;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.kneo.broadcaster.config.BroadcasterConfig;
import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.HashMap;

@ApplicationScoped
public class AgentClient {

    @Inject
    BroadcasterConfig config;

    @Inject
    Vertx vertx;

    private WebClient webClient;

    @PostConstruct
    void init() {
        this.webClient = WebClient.create(vertx);
    }

    public Uni<String> callRadioDjAgent(String provider, String template, Map<String, String> variables) {
        String endpoint = config.getAgentUrl() + "/radio_dj/test/" + provider;

        JsonObject payload = new JsonObject();
        payload.put("template", template);
        payload.put("variables", variables != null ? variables : new HashMap<>());

        return webClient
                .postAbs(endpoint)
                .putHeader("Content-Type", "application/json")
                .sendJsonObject(payload)
                .map(response -> {
                    if (response.statusCode() == 200) {
                        return response.bodyAsString();
                    } else {
                        throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.bodyAsString());
                    }
                });
    }
}