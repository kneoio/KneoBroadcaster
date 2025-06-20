package io.kneo.broadcaster.service.stream;

import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.service.SoundFragmentService;
import io.kneo.broadcaster.service.manipulation.AudioSegmentationService;
import io.kneo.broadcaster.service.radio.PlaylistManager;

public interface IStreamManager {

    void initialize();

    String generatePlaylist();

    HlsSegment getSegment(String segmentParam);

    long getLatestRequestedSeg();

    void shutdown();

    RadioStation getRadioStation();

    void setRadioStation(RadioStation radioStation);

    SoundFragmentService getSoundFragmentService();

    AudioSegmentationService getSegmentationService();

    StreamManagerStats getStats();

    PlaylistManager getPlaylistManager();

}