package io.kneo.broadcaster.server;

import io.kneo.broadcaster.controller.RadioController;
import io.kneo.broadcaster.controller.SoundFragmentController;
import io.kneo.core.server.ApplicationInit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class KneoBroadcasterApplicationInit extends ApplicationInit {

    @Inject
    SoundFragmentController soundFragmentController;

    @Inject
    RadioController radioController;

    @Override
    protected void setupRoutes() {
        super.setupRoutes();
        soundFragmentController.setupRoutes(router);
        radioController.setupRoutes(router);
        logRegisteredRoutes();
    }
}