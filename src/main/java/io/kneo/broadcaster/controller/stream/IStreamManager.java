package io.kneo.broadcaster.controller.stream;

import io.kneo.broadcaster.model.RadioStation; // Assuming RadioStation is needed for the getters/setters
import io.kneo.broadcaster.service.SoundFragmentService;
import io.kneo.broadcaster.service.manipulation.AudioSegmentationService;
import io.kneo.broadcaster.service.radio.PlaylistManager;

public interface IStreamManager {

    /**
     * Initializes the HLS stream manager, setting up timers and playlist management.
     */
    void initialize();

    /**
     * Generates and returns the current HLS playlist (.m3u8) string.
     *
     * @return The HLS playlist string.
     */
    String generatePlaylist();

    /**
     * Retrieves a specific HLS segment by its identifier (segment parameter).
     *
     * @param segmentParam The identifier of the requested segment (e.g., "radio_12345.ts").
     * @return The HlsSegment object, or null if not found.
     */
    HlsSegment getSegment(String segmentParam);

    /**
     * Returns the sequence number of the most recently requested segment.
     *
     * @return The sequence number of the last requested segment.
     */
    long getLatestRequestedSeg();

    /**
     * Shuts down the HLS stream manager, canceling timers and releasing resources.
     */
    void shutdown();

    /**
     * Gets the RadioStation associated with this stream manager.
     *
     * @return The RadioStation object.
     */
    RadioStation getRadioStation();

    /**
     * Sets the RadioStation for this stream manager.
     *
     * @param radioStation The RadioStation to set.
     */
    void setRadioStation(RadioStation radioStation);

    SoundFragmentService getSoundFragmentService();

    AudioSegmentationService getSegmentationService();

    HLSPlaylistStats getStats();

    PlaylistManager getPlaylistManager();

    // Other getters like getConfig(), getPlaylistManager() are typically
    // internal dependencies and might not need to be part of the public interface.
}