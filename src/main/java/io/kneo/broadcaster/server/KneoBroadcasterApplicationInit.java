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
    AiHelperController aiHelperController;

    @Inject
    PublicChatController publicChatController;


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
    BrandController brandController;

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
    private EventController eventController;

    @Inject
    private StreamController streamController;

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
        publicChatController.setupRoutes(router);
        aiHelperController.setupRoutes(router);
        dashboardController.setupRoutes(router);
        radioController.setupRoutes(router);
        queueController.setupRoutes(router);
        brandController.setupRoutes(router);
        listenerController.setupRoutes(router);
        eventController.setupRoutes(router);
        genreController.setupRoutes(router);
        aiAgentController.setupRoutes(router);
        profileController.setupRoutes(router);
        scriptController.setupRoutes(router);
        sceneController.setupRoutes(router);
        promptController.setupRoutes(router);
        draftController.setupRoutes(router);
        chatController.setupRoutes(router);
        streamController.setupRoutes(router);

        super.setupRoutes(router);
        logRegisteredRoutes(router);

        if (EnvConst.DEV_MODE) {
            LOGGER.info(EnvConst.APP_ID + "'s dev mode enabled");
        }
    }
}