package io.kneo.broadcaster.server;

import io.kneo.broadcaster.controller.AiHelperController;
import io.kneo.broadcaster.controller.DashboardController;
import io.kneo.broadcaster.controller.IcecastController;
import io.kneo.broadcaster.controller.ListenerController;
import io.kneo.broadcaster.controller.QueueController;
import io.kneo.broadcaster.controller.RadioController;
import io.kneo.broadcaster.controller.RadioStationController;
import io.kneo.broadcaster.controller.SoundFragmentController;
import io.kneo.broadcaster.service.FileMaintenanceService;
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
    AiHelperController aiHelperController;

    @Inject
    DashboardController dashboardController;

    @Inject
    SoundFragmentController soundFragmentController;

    @Inject
    RadioController radioController;

    // @Inject
    IcecastController icecastController;

    @Inject
    QueueController queueController;

    @Inject
    RadioStationController radioStationController;

    @Inject
    ListenerController listenerController;

    @Inject
    protected Router router;

    @Inject
    FileMaintenanceService fileMaintenanceService;

    @Inject
    public KneoBroadcasterApplicationInit(PgPool client, FileMaintenanceService fileMaintenanceService) {
        super(client);
        this.fileMaintenanceService = fileMaintenanceService;
        LOGGER.info("FileMaintenanceService instance created: {}", fileMaintenanceService != null);
    }

    // For DI
    public KneoBroadcasterApplicationInit() {
        super(null);
    }

    public void onStart(@Observes StartupEvent ev) {
        LOGGER.info("The application is starting...{}", EnvConst.APP_ID);
        super.setupRoutes(router);
        aiHelperController.setupRoutes(router);
        dashboardController.setupRoutes(router);
        soundFragmentController.setupRoutes(router);
        radioController.setupRoutes(router);
        // icecastController.setupRoutes(router);
        queueController.setupRoutes(router);
        radioStationController.setupRoutes(router);
        listenerController.setupRoutes(router);
        logRegisteredRoutes(router);

        if (EnvConst.DEV_MODE) {
            LOGGER.info(EnvConst.APP_ID + "'s dev mode enabled");
            // checkDatabaseConnection();
        }
    }
}