package io.kneo.broadcaster;

import io.kneo.broadcaster.queue.google.SoundFragmentSubscriber;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class ApplicationInit {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApplicationInit.class);

    @Inject
    Router router;

  //  @Inject
    SoundFragmentSubscriber soundFragmentSubscriber;

    void onStart(@Observes StartupEvent ev) {
        LOGGER.info("The application is starting...{}", EnvConst.APP_ID);

  //      soundFragmentSubscriber.init();
        LOGGER.info("SoundFragmentSubscriber initialized.");

        LOGGER.info("Registered routes:");
        for (Route route : router.getRoutes()) {
            LOGGER.info("{} {}", route.methods(), route.getPath());
        }
    }

    void onStop(@Observes ShutdownEvent ev) {
        LOGGER.info("The application is stopping...");
        soundFragmentSubscriber.shutdown();
    }
}
