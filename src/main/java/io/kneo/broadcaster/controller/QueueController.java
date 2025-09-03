package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.service.QueueService;
import io.kneo.broadcaster.service.RadioService;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class QueueController {
    private static final Logger LOGGER = LoggerFactory.getLogger(QueueController.class);

    private final QueueService service;
    private final RadioService radioService;
    private final BroadcasterConfig config;
    private final Vertx vertx;

    @Inject
    public QueueController(QueueService service, RadioService radioService, BroadcasterConfig config, Vertx vertx) {
        this.service = service;
        this.radioService = radioService;
        this.config = config;
        this.vertx = vertx;
    }

    public void setupRoutes(Router router) {
        String path = "/api/:brand/queue";
        router.route(HttpMethod.PUT, path + "/action").handler(this::action);
    }



    private void action(RoutingContext rc) {
        String brand = rc.pathParam("brand").toLowerCase();
        JsonObject jsonObject = rc.body().asJsonObject();
        String action = jsonObject.getString("action");
        if ("start".equalsIgnoreCase(action)) {
            LOGGER.info("Starting radio station for brand: {}", brand);
            radioService.initializeStation(brand)
                    .subscribe().with(
                            station -> {
                                rc.response()
                                        .putHeader("Content-Type", MediaType.APPLICATION_JSON)
                                        .setStatusCode(200)
                                        .end("{\"status\":\"" + station.getStatus() + "}");
                            },
                            throwable -> {
                                LOGGER.error("Error starting radio station: {}", throwable.getMessage());
                                rc.response()
                                        .setStatusCode(500)
                                        .putHeader("Content-Type", MediaType.TEXT_PLAIN)
                                        .end("Failed to start radio station: " + throwable.getMessage());
                            }
                    );
        } else if ("stop".equalsIgnoreCase(action)) {
            LOGGER.info("Stopping radio station for brand: {}", brand);
            radioService.stopStation(brand)
                    .subscribe().with(
                            station -> {
                                rc.response()
                                        .putHeader("Content-Type", MediaType.APPLICATION_JSON)
                                        .setStatusCode(200)
                                        .end("{\"status\":\"OK\"}");
                            },
                            throwable -> {
                                LOGGER.error("Error stopping radio station: {}", throwable.getMessage());
                                rc.response()
                                        .setStatusCode(500)
                                        .putHeader("Content-Type", MediaType.TEXT_PLAIN)
                                        .end("Failed to stop radio station: " + throwable.getMessage());
                            }
                    );
        } else {
            rc.response()
                    .setStatusCode(400)
                    .putHeader("Content-Type", MediaType.TEXT_PLAIN)
                    .end("Invalid action. Supported actions: 'start', 'stop'");
        }
    }
}