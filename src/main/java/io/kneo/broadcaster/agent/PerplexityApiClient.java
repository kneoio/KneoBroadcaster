package io.kneo.broadcaster.agent;

import io.kneo.broadcaster.config.PerplexityApiConfig;
import io.kneo.core.localization.LanguageCode;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.client.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

@ApplicationScoped
public class PerplexityApiClient {

    @Inject
    PerplexityApiConfig config;

    @Inject
    Vertx vertx;

    private WebClient webClient;

    @PostConstruct
    void init() {
        WebClientOptions options = new WebClientOptions()
                .setSsl(true)
                .setTrustAll(true)
                .setVerifyHost(false);
        this.webClient = WebClient.create(vertx, options);
    }

    public Uni<JsonObject> search(String query, List<LanguageCode> languages, List<String> domains) {

        JsonObject requestBody = new JsonObject()
                .put("model", "sonar-pro")
                .put("response_format", new JsonObject()
                        .put("type", "json_schema")
                        .put("json_schema", new JsonObject()
                                .put("schema", new JsonObject()
                                        .put("type", "object")
                                        .put("additionalProperties", true)
                                        .put("properties", new JsonObject())
                                )
                        )
                )
                .put("messages", List.of(
                        new JsonObject()
                                .put("role", "user")
                                .put("content", query + "\nRespond ONLY with valid JSON matching the schema.")
                ));


        if (!languages.isEmpty()) {
            requestBody.put("search_language_filter", languages.stream().map(LanguageCode::getAltCode).toList());
        }

        if (!domains.isEmpty()) {
            requestBody.put("search_domain_filter", domains);
        }


        return webClient
                .postAbs(config.getBaseUrl() + "/chat/completions")
                .putHeader("Authorization", "Bearer " + config.getApiKey())
                .putHeader("Content-Type", "application/json")
                .sendJsonObject(requestBody)
                .onItem().transform(response -> {
                    if (response.statusCode() != 200) {
                        throw new RuntimeException("Perplexity API error: " +
                                response.statusCode() + " - " + response.bodyAsString());
                    }

                    String content = response.bodyAsJsonObject()
                            .getJsonArray("choices")
                            .getJsonObject(0)
                            .getJsonObject("message")
                            .getString("content");

                    return new JsonObject(content);
                });

    }


    public static void main(String[] args) throws Exception {
        io.vertx.core.Vertx coreVertx = io.vertx.core.Vertx.vertx();
        PerplexityApiClient api = new PerplexityApiClient();

        api.vertx = new Vertx(coreVertx);
        api.webClient = WebClient.create(api.vertx);

        api.config = new PerplexityApiConfig() {
            @Override
            public String getApiKey() {
                return "xxx";
            }

            @Override
            public String getBaseUrl() {
                return "https://api.perplexity.ai";
            }
        };

        api.search("comparing prices for 10 eggs in PT and UA, " +
                        "show only the two countries as json", List.of(), List.of())
                .subscribe().with(
                        r -> {
                            System.out.println(r.encodePrettily());
                            coreVertx.close();
                        },
                        e -> {
                            System.err.println(e.getMessage());
                            coreVertx.close();
                        }
                );

        Thread.sleep(5000);
    }
}
