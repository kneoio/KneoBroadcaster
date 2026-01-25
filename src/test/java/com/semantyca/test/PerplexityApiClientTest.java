package com.semantyca.test;

import io.kneo.broadcaster.agent.PerplexityApiClient;
import io.kneo.core.localization.LanguageCode;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
public class PerplexityApiClientTest {

    @Inject
    PerplexityApiClient client;

    @Test
    public void testSearch() {
        JsonObject result = client.search("Latest news from Portugal",
                        List.of(LanguageCode.pt), List.of(), false)
                .await().indefinitely();

        System.out.println(result.encodePrettily());
        assertNotNull(result);
    }
}
