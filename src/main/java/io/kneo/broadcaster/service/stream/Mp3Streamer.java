package io.kneo.broadcaster.service.stream;

import io.kneo.broadcaster.config.HlsPlaylistConfig;
import io.kneo.broadcaster.model.live.LiveSoundFragment;
import io.kneo.broadcaster.service.playlist.PlaylistManager;
import io.smallrye.mutiny.Multi;
import io.vertx.core.buffer.Buffer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

@ApplicationScoped
public class Mp3Streamer {
    private static final Logger LOGGER = LoggerFactory.getLogger(Mp3Streamer.class);
    private final long segmentSleepTimeMillis;
    private final Map<String, Multi<Buffer>> activeStreams = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> listenerCounts = new ConcurrentHashMap<>();

    @Inject
    public Mp3Streamer(HlsPlaylistConfig config) {
        this.segmentSleepTimeMillis = (long) config.getSegmentDuration() * 1000L;
    }

    public void listenerJoined(String brand) {
        listenerCounts.computeIfAbsent(brand, b -> new AtomicInteger(0)).incrementAndGet();
    }

    public void listenerLeft(String brand) {
        int count = listenerCounts.computeIfAbsent(brand, b -> new AtomicInteger(0)).decrementAndGet();
        if (count <= 0) {
            listenerCounts.remove(brand);
            Multi<Buffer> stream = activeStreams.remove(brand);
            LOGGER.info("No listeners left, stopping stream for brand {}", brand);
        }
    }


    public Multi<Buffer> stream(PlaylistManager playlistManager) {
        String brand = playlistManager.getBrand();
        return activeStreams.computeIfAbsent(brand, b -> createSharedStream(playlistManager));
    }

    private Multi<Buffer> createSharedStream(PlaylistManager playlistManager) {

        return Multi.createFrom().emitter(emitter -> {
            Thread t = new Thread(() -> {
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        LiveSoundFragment fragment;
                        synchronized (playlistManager.getFragmentsForMp3()) {
                            if (playlistManager.getFragmentsForMp3().isEmpty()) {
                                Thread.sleep(200);
                                continue;
                            }
                            fragment = playlistManager.getFragmentsForMp3().peekFirst();
                        }


                        Map<Long, ConcurrentLinkedQueue<HlsSegment>> segmentQueues = fragment.getSegments();
                        ConcurrentLinkedQueue<HlsSegment> queue =
                                segmentQueues.get(segmentQueues.keySet().stream().findFirst().get());
                        for (HlsSegment seg : queue) {
                            emitter.emit(Buffer.buffer(seg.getData()));
                            Thread.sleep(segmentSleepTimeMillis);
                        }
                    }
                } catch (Exception e) {
                    emitter.fail(e);
                } finally {
                    emitter.complete();
                }
            }, "mp3-stream-" + playlistManager.getBrand());
            t.start();
            emitter.onTermination(t::interrupt);
        }).broadcast().toAllSubscribers().map(b -> (Buffer) b);

    }
}