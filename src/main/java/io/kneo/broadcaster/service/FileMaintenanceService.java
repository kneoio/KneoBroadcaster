package io.kneo.broadcaster.service;

import io.kneo.broadcaster.service.stream.FileJanitorTimer;
import io.smallrye.mutiny.subscription.Cancellable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class FileMaintenanceService {
    private static final Logger LOGGER = LoggerFactory.getLogger(FileMaintenanceService.class);

    private  FileJanitorTimer janitorTimer;

    @Inject
    public FileMaintenanceService(FileJanitorTimer janitorTimer) {
        Cancellable slider = janitorTimer.getTicker().subscribe().with(
                ts -> clean(),
                error -> LOGGER.error("Timer error", error)
        );


    }

    private void clean() {

    }
}
