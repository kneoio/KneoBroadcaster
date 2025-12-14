package io.kneo.broadcaster.service.stream;

import io.kneo.broadcaster.model.stream.IStream;
import io.kneo.broadcaster.service.manipulation.segmentation.AudioSegmentationService;
import io.kneo.broadcaster.service.playlist.PlaylistManager;
import io.kneo.broadcaster.service.soundfragment.SoundFragmentService;

public interface IStreamManager {

    void initialize();

    String generatePlaylist();

    HlsSegment getSegment(String segmentParam);

    HlsSegment getSegment(long sequence);

    void shutdown();

    IStream getStream();

    void setStream(IStream stream);

    SoundFragmentService getSoundFragmentService();

    AudioSegmentationService getSegmentationService();

    StreamManagerStats getStats();

    PlaylistManager getPlaylistManager();

}