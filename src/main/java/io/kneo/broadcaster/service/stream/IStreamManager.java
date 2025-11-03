package io.kneo.broadcaster.service.stream;

import io.kneo.broadcaster.model.radiostation.RadioStation;
import io.kneo.broadcaster.service.soundfragment.SoundFragmentService;
import io.kneo.broadcaster.service.manipulation.segmentation.AudioSegmentationService;
import io.kneo.broadcaster.service.playlist.PlaylistManager;

public interface IStreamManager {

    void initialize();

    String generatePlaylist();

    HlsSegment getSegment(String segmentParam);

    void shutdown();

    RadioStation getRadioStation();

    void setRadioStation(RadioStation radioStation);

    SoundFragmentService getSoundFragmentService();

    AudioSegmentationService getSegmentationService();

    StreamManagerStats getStats();

    PlaylistManager getPlaylistManager();

}