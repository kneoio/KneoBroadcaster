package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.server.EnvConst;
import io.kneo.broadcaster.service.dashboard.DashboardService;
import io.kneo.broadcaster.service.dashboard.StationDashboardService;
import io.kneo.core.dto.view.ViewPage;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class DashboardController {

    private final DashboardService dashboardService;
    private final StationDashboardService stationDashboardService;

    @Inject
    public DashboardController(DashboardService dashboardService,
                               StationDashboardService stationDashboardService) {
        this.dashboardService = dashboardService;
        this.stationDashboardService = stationDashboardService;
    }

    public void setupRoutes(Router router) {
        String path = "/api";
        router.route(path + "/*").handler(BodyHandler.create());

        router.route("/api/ws/dashboard").handler(rc -> {
            if ("websocket".equalsIgnoreCase(rc.request().getHeader("Upgrade"))) {
                rc.request().toWebSocket().onSuccess(this::handleDashboardWebSocket)
                        .onFailure(err -> {
                            System.err.println("WebSocket connection failed: " + err.getMessage());
                            rc.fail(500, err);
                        });
            } else {
                rc.response().setStatusCode(400).end("WebSocket upgrade required");
            }
        });

        router.route("/api/ws/dashboard/station/:brand").handler(rc -> {
            if ("websocket".equalsIgnoreCase(rc.request().getHeader("Upgrade"))) {
                String brand = rc.pathParam("brand");
                rc.request().toWebSocket().onSuccess(ws -> handleStation(ws, brand))
                        .onFailure(err -> {
                            System.err.println("WebSocket connection failed for brand " + brand + ": " + err.getMessage());
                            rc.fail(500, err);
                        });
            } else {
                rc.response().setStatusCode(400).end("WebSocket upgrade required");
            }
        });

        router.get("/api/dashboard").handler(this::getDashboard);
        router.get("/api/dashboard/station/:brand").handler(this::getStation);
    }

    private void handleDashboardWebSocket(ServerWebSocket webSocket) {
        webSocket.accept();
        sendDashboardData(webSocket);

        webSocket.textMessageHandler(message -> {
            JsonObject msgJson = new JsonObject(message);
            if ("getDashboard".equals(msgJson.getString("action"))) {
                sendDashboardData(webSocket);
            }
        });
    }

    private void handleStation(ServerWebSocket webSocket, String brand) {
        webSocket.accept();
        sendStationData(webSocket, brand);

        webSocket.textMessageHandler(message -> {
            JsonObject msgJson = new JsonObject(message);
            if ("getStation".equals(msgJson.getString("action"))) {
                sendStationData(webSocket, brand);
            }
        });
    }

    private void sendDashboardData(ServerWebSocket webSocket) {
        ViewPage viewPage = createBaseViewPage();
        dashboardService.getInfo()
                .subscribe().with(
                        poolStats -> {
                            viewPage.addPayload("Stats", poolStats);
                            webSocket.writeTextMessage(JsonObject.mapFrom(viewPage).encode());
                        },
                        err -> sendError(webSocket, err)
                );
    }

    private void sendStationData(ServerWebSocket webSocket, String brand) {
        ViewPage viewPage = createBaseViewPage();
        stationDashboardService.getStationStats(brand)
                .subscribe().with(
                        stats -> {
                            if (stats.isPresent()) {
                                viewPage.addPayload("Station", stats.get());
                                webSocket.writeTextMessage(JsonObject.mapFrom(viewPage).encode());
                            } else {
                                sendError(webSocket, "Station not found");
                            }
                        },
                        err -> sendError(webSocket, err)
                );
    }

    private void getDashboard(RoutingContext rc) {
        ViewPage viewPage = createBaseViewPage();
        dashboardService.getInfo()
                .subscribe().with(
                        poolStats -> {
                            viewPage.addPayload("Stats", poolStats);
                            rc.response().setStatusCode(200).end(JsonObject.mapFrom(viewPage).encode());
                        },
                        err -> handleError(rc, err)
                );
    }

    private void getStation(RoutingContext rc) {
        String brand = rc.pathParam("brand");
        ViewPage viewPage = createBaseViewPage();
        stationDashboardService.getStationStats(brand)
                .subscribe().with(
                        stats -> {
                            if (stats.isPresent()) {
                                viewPage.addPayload("Station", stats.get());
                                rc.response().setStatusCode(200).end(JsonObject.mapFrom(viewPage).encode());
                            } else {
                                handleError(rc, "Station not found", 404);
                            }
                        },
                        err -> handleError(rc, err)
                );
    }

    private ViewPage createBaseViewPage() {
        ViewPage viewPage = new ViewPage();
        viewPage.addPayload(EnvConst.APP_ID, EnvConst.VERSION);
        return viewPage;
    }

    private void sendError(ServerWebSocket webSocket, Throwable err) {
        webSocket.writeTextMessage(new JsonObject().put("error", err.getMessage()).encode());
    }

    private void sendError(ServerWebSocket webSocket, String message) {
        webSocket.writeTextMessage(new JsonObject().put("error", message).encode());
    }

    private void handleError(RoutingContext rc, Throwable err) {
        handleError(rc, err.getMessage(), 500);
    }

    private void handleError(RoutingContext rc, String message, int statusCode) {
        ViewPage viewPage = createBaseViewPage();
        viewPage.addPayload("Error", message);
        rc.response().setStatusCode(statusCode).end(JsonObject.mapFrom(viewPage).encode());
    }
}