package io.kneo.broadcaster.config;

import io.kneo.broadcaster.controller.stream.Playlist;
import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.service.RadioStationService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.HashMap;

@Singleton
public class RadioStationPool {
    private final HashMap<String, RadioStation> radioStations = new HashMap<>();

    @Inject
    private RadioStationService radioStationService;

    @Inject
    private HlsPlaylistConfig config;

    public void add(RadioStation radio) {
        radioStations.put(radio.getBrand(), radio);
    }

    public RadioStation get(String name) {

        Playlist playlist = new Playlist(config);
        RadioStation radioStation = radioStations.get(name);
        if (radioStation == null) {
            radioStation = new RadioStation();
            radioStation.setBrand(name);
            radioStation.setPlaylist(playlist);
            add(radioStation);
            return radioStation;
        }
        return radioStations.get(name);
    }


}
