package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.dto.QueueItemDTO;
import io.kneo.broadcaster.model.InterstitialPlaylistItem;
import io.kneo.broadcaster.service.QueueService;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class QueueController {
    private static final Logger LOGGER = LoggerFactory.getLogger(QueueController.class);

    private final QueueService service;

    @Inject
    public QueueController(QueueService service) {
        this.service = service;
    }

    public void setupRoutes(Router router) {
        String path = "/:brand/queue";
        router.route(HttpMethod.GET, path).handler(this::get);
        router.route(HttpMethod.POST, path).handler(this::add);
    }

    private void get(RoutingContext ctx) {
        String brand = ctx.pathParam("brand");

        service.getQueuedItems(brand)
                .subscribe().with(
                        items -> {
                            List<QueueItemDTO> dtos = items.stream()
                                    .map(this::convertToDTO)
                                    .collect(Collectors.toList());

                            ctx.response()
                                    .putHeader("Content-Type", "application/json")
                                    .end(Json.encode(dtos));
                        },
                        error -> ctx.fail(500, error)
                );
    }

    private void add(RoutingContext rc) {
        String brand = rc.pathParam("brand");

        try {
            JsonObject jsonObject = rc.body().asJsonObject();
            QueueItemDTO dto = jsonObject.mapTo(QueueItemDTO.class);
            String filePath = jsonObject.getString("filePath", "/audio/default.mp3");

            InterstitialPlaylistItem item = new InterstitialPlaylistItem(
                    Path.of(filePath),
                    dto.getMetadata(),
                    dto.getType(),
                    dto.getPriority() > 0 ? dto.getPriority() : 100
            );

            service.addItem(brand, item)
                    .subscribe().with(
                            success -> {
                                QueueItemDTO responseDto = convertToDTO(item);
                                responseDto.setId(item.getId());

                                rc.response()
                                        .setStatusCode(201)
                                        .putHeader("Content-Type", "application/json")
                                        .end(Json.encode(responseDto));
                            },
                            error -> rc.fail(500, error)
                    );
        } catch (Exception e) {
            LOGGER.error("Error adding to queue: {}", e.getMessage());
            rc.fail(400, e);
        }
    }

    private QueueItemDTO convertToDTO(InterstitialPlaylistItem item) {
        QueueItemDTO dto = new QueueItemDTO();
        dto.setId(item.getId());
        dto.setMetadata(item.getMetadata());
        dto.setType(item.getType());
        dto.setPriority(item.getPriority());
        return dto;
    }
}