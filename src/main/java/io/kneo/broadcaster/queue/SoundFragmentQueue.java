package io.kneo.broadcaster.queue;

import io.kneo.broadcaster.model.SoundFragment;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

@ApplicationScoped
public class SoundFragmentQueue {
    private static final Logger LOGGER = LoggerFactory.getLogger(SoundFragmentSubscriber.class);
    private final BlockingQueue<SoundFragment> soundFragments = new LinkedBlockingQueue<>();

    public void addSoundFragment(SoundFragment fragment) {
        soundFragments.offer(fragment);
    }

    public SoundFragment takeSoundFragment() throws InterruptedException {
        return soundFragments.take();
    }
}
