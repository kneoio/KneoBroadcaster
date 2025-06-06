package io.kneo.broadcaster.service;

import io.kneo.broadcaster.config.HlsPlaylistConfig;
import io.kneo.broadcaster.dto.aihelper.BrandInfo;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.model.cnst.ManagedBy;
import io.kneo.broadcaster.service.stream.RadioStationPool;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class AiHelperService {

    @Inject
    HlsPlaylistConfig config;

    @Inject
    RadioStationPool radioStationPool;

    public Uni<List<BrandInfo>> XgetByStatus(List<RadioStationStatus> statuses) {
        return Uni.createFrom().item(() ->
                radioStationPool.getOnlineStationsSnapshot().stream()
                        .filter(station -> station.getManagedBy() != ManagedBy.ITSELF)
                        .filter(station -> statuses.contains(station.getStatus()))
                        .map(station -> {
                            BrandInfo brand = new BrandInfo();
                            brand.setRadioStationName(station.getSlugName());
                            brand.setRadioStationStatus(station.getStatus());
                            return brand;
                        })
                        .collect(Collectors.toList())
        );
    }
}