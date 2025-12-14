package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.dto.stream.OneTimeStreamDTO;
import io.kneo.broadcaster.model.stream.IStream;
import io.kneo.broadcaster.model.stream.OneTimeStream;
import io.kneo.broadcaster.service.stream.RadioStationPool;
import io.kneo.core.controller.AbstractSecuredController;
import io.kneo.core.dto.cnst.PayloadType;
import io.kneo.core.dto.view.View;
import io.kneo.core.dto.view.ViewPage;
import io.kneo.core.service.UserService;
import io.kneo.core.util.RuntimeUtil;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class StreamController extends AbstractSecuredController<IStream, OneTimeStreamDTO> {
    private static final Logger LOGGER = LoggerFactory.getLogger(StreamController.class);

    private RadioStationPool radioStationPool;

    public StreamController() {
        super(null);
    }

    @Inject
    public StreamController(UserService userService, RadioStationPool radioStationPool) {
        super(userService);
        this.radioStationPool = radioStationPool;
    }

    public void setupRoutes(Router router) {
        String path = "/api/streams";
        router.get(path).handler(this::getAll);
        router.get(path + "/:slugName").handler(this::getBySlugName);
    }

    private void getAll(RoutingContext rc) {
        int page = Integer.parseInt(rc.request().getParam("page", "1"));
        int size = Integer.parseInt(rc.request().getParam("size", "10"));

        getContextUser(rc, false, true)
                .chain(user -> {
                    List<IStream> allStreams = new ArrayList<>(radioStationPool.getOnlineStationsSnapshot());

                    int total = allStreams.size();
                    int fromIndex = Math.min((page - 1) * size, total);
                    int toIndex = Math.min(fromIndex + size, total);
                    List<IStream> pagedStreams = allStreams.subList(fromIndex, toIndex);

                    List<OneTimeStreamDTO> dtos = pagedStreams.stream()
                            .map(this::toDTO)
                            .collect(Collectors.toList());

                    ViewPage viewPage = new ViewPage();
                    View<OneTimeStreamDTO> dtoEntries = new View<>(dtos,
                            total, page,
                            RuntimeUtil.countMaxPage(total, size),
                            size);
                    viewPage.addPayload(PayloadType.VIEW_DATA, dtoEntries);
                    return Uni.createFrom().item(viewPage);
                })
                .subscribe().with(
                        viewPage -> rc.response().setStatusCode(200).end(JsonObject.mapFrom(viewPage).encode()),
                        throwable -> {
                            LOGGER.error("Failed to get all streams", throwable);
                            rc.fail(throwable);
                        }
                );
    }

    private void getBySlugName(RoutingContext rc) {
        String slugName = rc.pathParam("slugName");

        getContextUser(rc, false, true)
                .chain(user -> {
                    return radioStationPool.get(slugName)
                            .map(stream -> {
                                if (stream == null) {
                                    return null;
                                }
                                return toDTO(stream);
                            });
                })
                .subscribe().with(
                        dto -> {
                            if (dto == null) {
                                rc.response().setStatusCode(404).end();
                            } else {
                                rc.response().setStatusCode(200).end(JsonObject.mapFrom(dto).encode());
                            }
                        },
                        throwable -> {
                            LOGGER.error("Failed to get stream by slugName: {}", slugName, throwable);
                            rc.fail(throwable);
                        }
                );
    }

    private OneTimeStreamDTO toDTO(IStream stream) {
        OneTimeStreamDTO dto = new OneTimeStreamDTO();
        dto.setId(stream.getId());
        dto.setSlugName(stream.getSlugName());
        dto.setLocalizedName(stream.getLocalizedName());
        dto.setTimeZone(stream.getTimeZone() != null ? stream.getTimeZone().getId() : null);
        dto.setBitRate(stream.getBitRate());
        dto.setStatus(stream.getStatus());

        if (stream instanceof OneTimeStream ots) {
            dto.setBaseBrandId(ots.getBaseBrandId());
            dto.setCreatedAt(ots.getCreatedAt());
            dto.setExpiresAt(ots.getExpiresAt());
        }

        return dto;
    }

}
