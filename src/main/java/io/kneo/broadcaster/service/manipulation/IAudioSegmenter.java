package io.kneo.broadcaster.service.manipulation;

import io.kneo.broadcaster.controller.stream.HlsSegment; // Needed for return type
import io.kneo.broadcaster.model.SoundFragment; // Needed for parameter
import io.kneo.broadcaster.model.SegmentInfo; // Needed for return type in segmentAudioFile
import java.nio.file.Path; // Needed for parameter
import java.util.List; // Needed for return type in segmentAudioFile
import java.util.UUID; // Needed for parameter
import java.util.concurrent.ConcurrentLinkedQueue; // Needed for return type in slice

public interface IAudioSegmenter {

    /**
     * Slices a given SoundFragment into a queue of HlsSegment objects.
     *
     * @param soundFragment The SoundFragment to be sliced.
     * @return A ConcurrentLinkedQueue of HlsSegment objects.
     */
    ConcurrentLinkedQueue<HlsSegment> slice(SoundFragment soundFragment);

    /**
     * Segments an audio file at the specified path into smaller audio segments using FFmpeg.
     *
     * @param audioFilePath  The path to the input audio file.
     * @param songMetadata   Metadata about the song (e.g., title, artist).
     * @param fragmentId     The UUID of the sound fragment this audio file belongs to.
     * @return A List of SegmentInfo objects, each representing a created audio segment.
     */
    List<SegmentInfo> segmentAudioFile(Path audioFilePath, String songMetadata, UUID fragmentId);

    // Note: The static 'setSegmentDuration' method is not included in the interface
    // as static methods are not part of an object's contract and should be configured
    // via constructor injection or a configuration mechanism.
}