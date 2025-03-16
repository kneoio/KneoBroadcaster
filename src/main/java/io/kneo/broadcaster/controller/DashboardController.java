package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.dto.ListenerDTO;
import io.kneo.broadcaster.model.Listener;
import io.kneo.broadcaster.server.EnvConst;
import io.kneo.broadcaster.service.DashboardService;
import io.kneo.core.controller.AbstractSecuredController;
import io.kneo.core.dto.view.ViewPage;
import io.kneo.core.service.UserService;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.core.http.ServerWebSocket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class DashboardController extends AbstractSecuredController<Listener, ListenerDTO> {

    private final DashboardService dashboardService;
    private final Map<String, ServerWebSocket> connectedClients = new ConcurrentHashMap<>();

    public DashboardController() {
        super(null);
        this.dashboardService = null;
    }

    @Inject
    public DashboardController(UserService userService, DashboardService dashboardService) {
        super(userService);
        this.dashboardService = dashboardService;
    }

    public void setupRoutes(Router router) {
        String path = "/api/dashboard";
        router.route().handler(BodyHandler.create());

        router.get("/api/ws/dashboard").handler(rc -> {
            rc.request().toWebSocket().onSuccess(this::handleWebSocket)
                    .onFailure(err -> rc.fail(500, err));
        });

        router.route(path + "*").handler(this::addHeaders);
        router.get(path).handler(this::get);
    }

    private void handleWebSocket(ServerWebSocket webSocket) {
        String clientId = webSocket.textHandlerID();

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
        ViewPage viewPage = new ViewPage();
        List<String> values = new ArrayList<>();
        values.add(EnvConst.VERSION);
        viewPage.addPayload(EnvConst.APP_ID, values);

        dashboardService.getPoolInfo(null)
                .subscribe().with(
                        poolStats -> {
                            viewPage.addPayload("Stats", poolStats);
                            webSocket.writeTextMessage(JsonObject.mapFrom(viewPage).encode());
                        },
                        err -> {
                            JsonObject errorJson = new JsonObject()
                                    .put("error", err.getMessage());
                            webSocket.writeTextMessage(errorJson.encode());
                        }
                );
    }

    public void broadcastUpdate() {
        connectedClients.values().forEach(this::sendDashboardData);
    }

    private void get(RoutingContext rc) {
        getContextUser(rc)
                .flatMap(user -> {
                    ViewPage viewPage = new ViewPage();
                    List<String> values = new ArrayList<>();
                    values.add(EnvConst.VERSION);
                    viewPage.addPayload(EnvConst.APP_ID, values);
                    return dashboardService.getPoolInfo(null)
                            .onItem().transform(poolStats -> {
                                viewPage.addPayload("Stats", poolStats);
                                return viewPage;
                            });
                })
                .subscribe().with(
                        viewPage -> rc.response().setStatusCode(200).end(JsonObject.mapFrom(viewPage).encode()),
                        rc::fail
                );
    }
}