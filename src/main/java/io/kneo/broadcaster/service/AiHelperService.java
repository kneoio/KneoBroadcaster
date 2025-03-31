package io.kneo.broadcaster.service;

import io.kneo.broadcaster.config.HlsPlaylistConfig;
import io.kneo.broadcaster.dto.aihelper.BrandInfo;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.service.stream.RadioStationPool;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.List;

@ApplicationScoped
public class AiHelperService {

    @Inject
    HlsPlaylistConfig config;

    @Inject
    RadioStationPool radioStationPool;

    public Uni<List<BrandInfo>> getBrandStatus(List<RadioStationStatus> statuses) {
        HashMap<String, RadioStation> pool = radioStationPool.getPool();

        List<BrandInfo> onlineBrands = pool.values().stream()
                .filter(station -> statuses.contains(station.getStatus()))
                .map(v -> {
                    BrandInfo brand = new BrandInfo();
                    brand.setRadioStationName(v.getSlugName());
                    brand.setRadioStationStatus(v.getStatus()); // Use the station's actual status
                    return brand;
                })
                .toList();
        return Uni.createFrom().item(onlineBrands);
    }
}