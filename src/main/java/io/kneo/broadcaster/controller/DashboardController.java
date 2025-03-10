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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class DashboardController extends AbstractSecuredController<Listener, ListenerDTO> {

    private DashboardService dashboardService;

    public DashboardController() {
        super(null);
    }

    @Inject
    public DashboardController(UserService userService, DashboardService dashboardService) {
        super(userService);
        this.dashboardService = dashboardService;
    }

    public void setupRoutes(Router router) {
        String path = "/api/dashboard";
        router.route().handler(BodyHandler.create());
        router.route(path + "*").handler(this::addHeaders);
        router.get(path).handler(this::get);

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
