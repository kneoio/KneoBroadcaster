package io.kneo.broadcaster.processor;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class AudioProcessorScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(AudioProcessorScheduler.class);

    @Inject
    AudioProcessor audioProcessor;

    @Scheduled(every = "5s")
    public void updateFiles() {
        LOGGER.info("Scheduling audio file processing");
        audioProcessor.processUnprocessedFragments();
    }
}
