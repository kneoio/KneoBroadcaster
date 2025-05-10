package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.service.RadioService;
import io.kneo.broadcaster.service.exceptions.RadioStationException;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@ApplicationScoped
public class IcecastController {
    private static final Logger LOGGER = LoggerFactory.getLogger(IcecastController.class);

    private final RadioService service;
    private final Map<String, StreamData> activeStreams = new ConcurrentHashMap<>();
    private final AtomicInteger listenerCount = new AtomicInteger(0);

    @Inject
    public IcecastController(RadioService service) {
        this.service = service;
    }

    public void setupRoutes(Router router) {
        String path = "/:brand/radio";
        router.route(HttpMethod.GET, path + "/icecast").handler(this::handleListener);
        router.route(HttpMethod.GET, path + "/status").handler(this::getStatus);
    }

    private void handleListener(RoutingContext rc) {
        String brand = rc.pathParam("brand");
        listenerCount.incrementAndGet();

        // Get or create stream for this brand
        StreamData streamData = activeStreams.computeIfAbsent(brand, k -> new StreamData());

        // Setup the response
        rc.response()
                .putHeader("Content-Type", "audio/mpeg")
                .putHeader("icy-name", brand + " Radio")
                .putHeader("icy-description", "Radio stream for " + brand)
                .putHeader("icy-genre", "Various")
                .putHeader("icy-pub", "1")
                .putHeader("Cache-Control", "no-cache")
                .putHeader("Access-Control-Allow-Origin", "*")
                .setChunked(true);

        // Add this client as a listener
        streamData.addListener(rc);

        // Start streaming if this is the first listener
        if (streamData.getListenerCount() == 1) {
            startStreaming(brand, streamData);
        }
    }

    private void startStreaming(String brand, StreamData streamData) {
        service.getPlaylist(brand, null)
                .subscribe().with(
                        playlist -> {
                            // Start a continuous loop to feed audio to listeners
                            //streamAudio(brand, playlist.getFirstSegmentKey(), streamData);
                        },
                        throwable -> {
                            LOGGER.error("Error starting stream for brand: {} - {}", brand, throwable.getMessage());
                            if (throwable instanceof RadioStationException) {
                                streamData.notifyError(404, throwable.getMessage());
                            } else {
                                streamData.notifyError(500, "Internal server error");
                            }
                            activeStreams.remove(brand);
                        }
                );
    }


    private void getStatus(RoutingContext rc) {
        String brand = rc.pathParam("brand");
        StreamData stream = activeStreams.get(brand);

        int listeners = stream != null ? stream.getListenerCount() : 0;

        rc.response()
                .putHeader("Content-Type", MediaType.APPLICATION_JSON)
                .end("{ \"brand\": \"" + brand + "\", \"listeners\": " + listeners + ", \"totalListeners\": " + listenerCount.get() + " }");
    }

    // Helper class to manage stream data and listeners
    private static class StreamData {
        private final Map<RoutingContext, Long> listeners = new ConcurrentHashMap<>();

        public void addListener(RoutingContext rc) {
            listeners.put(rc, System.currentTimeMillis());

            // Handle client disconnection
            rc.response().closeHandler(v -> {
                listeners.remove(rc);
                LOGGER.info("Listener disconnected, remaining: {}", listeners.size());
            });
        }

        public void sendData(byte[] data) {
            Buffer buffer = Buffer.buffer(data);

            // Send to all connected listeners
            listeners.forEach((rc, timestamp) -> {
                if (!rc.response().closed()) {
                    rc.response().write(buffer);
                } else {
                    listeners.remove(rc);
                }
            });
        }

        public void notifyError(int statusCode, String message) {
            listeners.forEach((rc, timestamp) -> {
                if (!rc.response().closed()) {
                    rc.response().setStatusCode(statusCode).end(message);
                }
                listeners.remove(rc);
            });
        }

        public int getListenerCount() {
            return listeners.size();
        }
    }
}