import io.kneo.broadcaster.controller.stream.HlsSegment;
import io.kneo.broadcaster.controller.stream.PlaylistFragmentRange;
import io.kneo.broadcaster.model.SoundFragment;
import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.model.cnst.SourceType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;

public class QueuePopulator {

    private final Map<Integer, PlaylistFragmentRange> mainQueue = Collections.synchronizedMap(new LinkedHashMap<>());
    private final AtomicInteger rangeCounter = new AtomicInteger(0);

    public void addFragmentToQueue(String title, int durationSeconds) {
        int key = rangeCounter.getAndIncrement();

        SoundFragment fragment = new SoundFragment();
        fragment.setId(UUID.randomUUID());
        fragment.setTitle(title);
        fragment.setArtist("Artist " + key);
        fragment.setType(PlaylistItemType.SONG);
        fragment.setSource(SourceType.LOCAL);
        fragment.setStatus(1);

        ConcurrentNavigableMap<Long, HlsSegment> segments = new ConcurrentSkipListMap<>();
        long startSeq = key * 100L;
        int segmentDuration = 1;
        int duration = 0;
        long numberOfSegments = Math.max(1, durationSeconds / segmentDuration);
        long endSeq = startSeq + numberOfSegments - 1;

        for (long currentSeq = startSeq; currentSeq <= endSeq; currentSeq++) {
            HlsSegment segment = new HlsSegment(
                    currentSeq,
                    new byte[512],
                    segmentDuration,
                    fragment.getId(),
                    fragment.getTitle(),
                    System.currentTimeMillis()
            );
            segments.put(currentSeq, segment);
            duration += segmentDuration;
        }

        PlaylistFragmentRange range = new PlaylistFragmentRange(segments, startSeq, endSeq, duration, fragment);
        mainQueue.put(key, range);
        System.out.println("  Added: Key " + key + " -> " + range + " containing " + fragment);
    }

    public Map<Integer, PlaylistFragmentRange> getQueueMap() {
        return mainQueue;
    }
}