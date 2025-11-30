package io.kneo.broadcaster.server;

import io.kneo.broadcaster.controller.AiAgentController;
import io.kneo.broadcaster.controller.AiHelperController;
import io.kneo.broadcaster.controller.ChatController;
import io.kneo.broadcaster.controller.DashboardController;
import io.kneo.broadcaster.controller.DraftController;
import io.kneo.broadcaster.controller.IcecastController;
import io.kneo.broadcaster.controller.ListenerController;
import io.kneo.broadcaster.controller.MessagingController;
import io.kneo.broadcaster.controller.ProfileController;
import io.kneo.broadcaster.controller.PromptController;
import io.kneo.broadcaster.controller.QueueController;
import io.kneo.broadcaster.controller.RadioController;
import io.kneo.broadcaster.controller.RadioStationController;
import io.kneo.broadcaster.controller.RefController;
import io.kneo.broadcaster.controller.SceneController;
import io.kneo.broadcaster.controller.ScriptController;
import io.kneo.broadcaster.controller.SoundFragmentBulkUploadController;
import io.kneo.broadcaster.controller.SoundFragmentController;
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
    MessagingController messagingController;


    @Inject
    DashboardController dashboardController;

    @Inject
    SoundFragmentController soundFragmentController;

    @Inject
    SoundFragmentBulkUploadController soundFragmentBulkUploadController;

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
    AiAgentController aiAgentController;

    @Inject
    ProfileController profileController;


    @Inject
    RefController genreController;

    @Inject
    protected Router router;

    @Inject
    ScriptController scriptController;

    @Inject
    SceneController sceneController;

    @Inject
    PromptController promptController;

    @Inject
    DraftController draftController;

    @Inject
    ChatController chatController;

    @Inject
    public KneoBroadcasterApplicationInit(PgPool client) {
        super(client);
    }

    // For DI
    public KneoBroadcasterApplicationInit() {
        super(null);
    }

    public void onStart(@Observes StartupEvent ev) {
        soundFragmentController.setupRoutes(router);
        soundFragmentBulkUploadController.setupRoutes(router);
        messagingController.setupRoutes(router);
        aiHelperController.setupRoutes(router);
        dashboardController.setupRoutes(router);
        radioController.setupRoutes(router);
        queueController.setupRoutes(router);
        radioStationController.setupRoutes(router);
        listenerController.setupRoutes(router);
        genreController.setupRoutes(router);
        aiAgentController.setupRoutes(router);
        profileController.setupRoutes(router);
        scriptController.setupRoutes(router);
        sceneController.setupRoutes(router);
        promptController.setupRoutes(router);
        draftController.setupRoutes(router);
        chatController.setupRoutes(router);

        super.setupRoutes(router);
        logRegisteredRoutes(router);

        if (EnvConst.DEV_MODE) {
            LOGGER.info(EnvConst.APP_ID + "'s dev mode enabled");
        }
    }
}