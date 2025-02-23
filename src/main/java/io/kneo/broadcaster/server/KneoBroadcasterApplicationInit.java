package io.kneo.broadcaster.server;

import io.kneo.broadcaster.controller.*;

import io.kneo.core.server.AbstractApplicationInit;
import io.quarkus.runtime.StartupEvent;
import io.vertx.ext.web.Router;
import io.vertx.mutiny.pgclient.PgPool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


@ApplicationScoped
public class KneoBroadcasterApplicationInit extends AbstractApplicationInit {
    private static final Logger LOGGER = LoggerFactory.getLogger(KneoBroadcasterApplicationInit.class);

    @Inject
    DashboardController dashboardController;

    @Inject
    SoundFragmentController soundFragmentController;

    @Inject
    RadioController radioController;

    @Inject
    RadioStationController radioStationController;

    @Inject
    ListenerController listenerController;


    @Inject
    protected Router router;

    @Inject
    public KneoBroadcasterApplicationInit(PgPool client)  {
        super(client);
    }

    //For DI
    public KneoBroadcasterApplicationInit() {
        super(null);
    }

    public void onStart(@Observes StartupEvent ev) {
        LOGGER.info("The application is starting...{}", EnvConst.APP_ID);
        super.setupRoutes(router);
        dashboardController.setupRoutes(router);
        soundFragmentController.setupRoutes(router);
        radioController.setupRoutes(router);
        radioStationController.setupRoutes(router);
        listenerController.setupRoutes(router);
        logRegisteredRoutes(router);

        if (EnvConst.DEV_MODE) {
            LOGGER.info(EnvConst.APP_ID + "'s dev mode enabled");
            //checkDatabaseConnection();
        }
    }
}