package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.server.EnvConst;
import io.kneo.broadcaster.service.DashboardService;
import io.kneo.core.dto.view.ViewPage;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class DashboardController {

    private final DashboardService dashboardService;
    private final Map<String, ServerWebSocket> connectedClients = new ConcurrentHashMap<>();


    @Inject
    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    public void setupRoutes(Router router) {
        router.route().handler(BodyHandler.create());
        router.get("/api/ws/dashboard").handler(rc -> {
            rc.request().toWebSocket().onSuccess(this::handleWebSocket)
                    .onFailure(err -> rc.fail(500, err));
        });

       // router.route(path + "*").handler(this::addHeaders);
        router.get("/api/dashboard").handler(this::get);
    }

    private void handleWebSocket(ServerWebSocket webSocket) {
        String clientId = java.util.UUID.randomUUID().toString();
        webSocket.accept();
        connectedClients.put(clientId, webSocket);
        sendDashboardData(webSocket);
        webSocket.closeHandler(v -> connectedClients.remove(clientId));

        webSocket.textMessageHandler(message -> {
            JsonObject msgJson = new JsonObject(message);
            String action = msgJson.getString("action", "");

            if ("getDashboard".equals(action)) {
                sendDashboardData(webSocket);
            }
        });
    }

    private void sendDashboardData(ServerWebSocket webSocket) {
        if (dashboardService == null) {
            JsonObject errorJson = new JsonObject()
                    .put("error", "Service unavailable");
            webSocket.writeTextMessage(errorJson.encode());
            return;
        }

        ViewPage viewPage = new ViewPage();
        List<String> values = new ArrayList<>();
        values.add(EnvConst.VERSION);
        viewPage.addPayload(EnvConst.APP_ID, values);

        dashboardService.getPoolInfo()
                .subscribe().with(
                        poolStats -> {
                            viewPage.addPayload("Stats", poolStats);
                            String responseJson = JsonObject.mapFrom(viewPage).encode();
                            webSocket.writeTextMessage(responseJson);
                        },
                        err -> {
                            JsonObject errorJson = new JsonObject()
                                    .put("error", err.getMessage());
                            webSocket.writeTextMessage(errorJson.encode());
                        }
                );
    }

    private void get(RoutingContext rc) {
        ViewPage viewPage = new ViewPage();
        List<String> values = new ArrayList<>();
        values.add(EnvConst.VERSION);
        viewPage.addPayload(EnvConst.APP_ID, values);

        assert dashboardService != null;
        dashboardService.getPoolInfo()
                .subscribe().with(
                        poolStats -> {
                            viewPage.addPayload("Stats", poolStats);
                            rc.response().setStatusCode(200).end(JsonObject.mapFrom(viewPage).encode());
                        },
                        err -> {
                            viewPage.addPayload("Error", err.getMessage());
                            rc.response().setStatusCode(500).end(JsonObject.mapFrom(viewPage).encode());
                        }
                );
    }
}