package io.kneo.broadcaster.service.manipulation.segmentation;

import io.kneo.broadcaster.service.stream.HlsSegment;
import io.kneo.broadcaster.model.soundfragment.SoundFragment;
import io.kneo.broadcaster.model.SegmentInfo;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

public interface IAudioSegmenter {

    ConcurrentLinkedQueue<HlsSegment> slice(SoundFragment soundFragment);

    List<SegmentInfo> segmentAudioFile(Path audioFilePath, String songMetadata, UUID fragmentId);

}