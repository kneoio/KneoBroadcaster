package io.kneo.broadcaster.service;

import io.kneo.broadcaster.dto.RadioStationStatusDTO;
import io.kneo.broadcaster.dto.cnst.AiAgentStatus;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.repository.RadioStationRepository;
import io.kneo.broadcaster.service.exceptions.RadioStationException;
import io.kneo.broadcaster.service.stream.IStreamManager;
import io.kneo.broadcaster.service.stream.RadioStationPool;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.SuperUser;
import io.kneo.officeframe.cnst.CountryCode;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class RadioService {
    private static final Logger LOGGER = LoggerFactory.getLogger(RadioService.class);

    @Inject
    RadioStationPool radioStationPool;

    @Inject
    RadioStationRepository radioStationRepository;

    @Inject
    AiAgentService aiAgentService;

    @Inject
    RadioStationService radioStationService;

    @Inject
    AnimationService animationService;

    public Uni<RadioStation> initializeStation(String brand) {
        LOGGER.info("Initializing station for brand: {}", brand);
        return radioStationPool.initializeStation(brand)
                .onFailure().invoke(failure -> {
                    LOGGER.error("Failed to initialize station for brand: {}", brand, failure);
                    radioStationPool.get(brand)
                            .subscribe().with(
                                    station -> {
                                        if (station != null) {
                                            station.setStatus(RadioStationStatus.SYSTEM_ERROR);
                                            LOGGER.warn("Station {} status set to SYSTEM_ERROR due to initialization failure", brand);
                                        }
                                    },
                                    error -> LOGGER.error("Failed to get station {} to set error status: {}", brand, error.getMessage(), error)
                            );
                });
    }

    public Uni<RadioStation> stopStation(String brand) {
        LOGGER.info("Stop brand: {}", brand);
        return radioStationPool.stopAndRemove(brand)
                .onFailure().invoke(failure ->
                        LOGGER.error("Failed to stop station for brand: {}", brand, failure)
                );
    }

    public Uni<IStreamManager> getPlaylist(String brand, String userAgent, boolean updateAccessTime) {
        if (updateAccessTime) {
            return recordAccess(brand, userAgent)
                    .onFailure().recoverWithItem(() -> {
                        LOGGER.warn("Failed to record access, but continuing with playlist retrieval: {}", brand);
                        return null;
                    })
                    .chain(() -> getPlaylistInternal(brand));
        } else {
            return getPlaylistInternal(brand);
        }
    }

    private Uni<IStreamManager> getPlaylistInternal(String brand) {
        return radioStationPool.get(brand)
                .onItem().ifNull().failWith(() ->
                        new RadioStationException(RadioStationException.ErrorType.STATION_NOT_ACTIVE)
                )
                .onItem().transform(RadioStation::getPlaylist)
                .onItem().ifNull().failWith(() ->
                        new RadioStationException(RadioStationException.ErrorType.PLAYLIST_NOT_AVAILABLE)
                );
    }

    public Uni<Void> recordAccess(String brand, String userAgent) {
        return radioStationRepository.upsertStationAccess(brand, userAgent)
                .onFailure().invoke(failure ->
                        LOGGER.error("Failed to record access for brand: {}, userAgent: {}", brand, userAgent, failure)
                );
    }

    public Uni<RadioStationStatusDTO> getStatus(String brand, String userAgent) {
        return getPlaylist(brand, userAgent, false)
                .onItem().transform(IStreamManager::getRadioStation)
                .chain(this::toStatusDTO);
    }

    public Uni<List<RadioStationStatusDTO>> getStations() {
        return Uni.combine().all().unis(
                getOnlineStations(),
                radioStationService.getAll(1000, 0)
        ).asTuple().chain(tuple -> {
            List<RadioStation> onlineStations = tuple.getItem1();
            List<RadioStation> allStations = tuple.getItem2();

            List<String> onlineBrands = onlineStations.stream()
                    .map(RadioStation::getSlugName)
                    .toList();

            List<Uni<RadioStationStatusDTO>> onlineStatusUnis = onlineStations.stream()
                    .map(this::toStatusDTO)
                    .collect(Collectors.toList());

            List<Uni<RadioStationStatusDTO>> offlineStatusUnis = allStations.stream()
                    .filter(station -> !onlineBrands.contains(station.getSlugName()))
                    .map(this::toStatusDTO)
                    .collect(Collectors.toList());

            Uni<List<RadioStationStatusDTO>> onlineResultsUni = onlineStatusUnis.isEmpty()
                    ? Uni.createFrom().item(List.of())
                    : Uni.join().all(onlineStatusUnis).andFailFast();

            Uni<List<RadioStationStatusDTO>> offlineResultsUni = offlineStatusUnis.isEmpty()
                    ? Uni.createFrom().item(List.of())
                    : Uni.join().all(offlineStatusUnis).andFailFast();

            return Uni.combine().all().unis(onlineResultsUni, offlineResultsUni)
                    .asTuple().map(results -> {
                        List<RadioStationStatusDTO> onlineResults = results.getItem1();
                        List<RadioStationStatusDTO> offlineResults = results.getItem2();

                        List<RadioStationStatusDTO> combined = new ArrayList<>();
                        combined.addAll(onlineResults);
                        combined.addAll(offlineResults);

                        return combined;
                    });
        }).onFailure().invoke(failure ->
                LOGGER.error("Failed to get stations", failure)
        );
    }

    public Uni<List<RadioStationStatusDTO>> getAllStations() {
        return radioStationService.getAllDTO(5, 0, SuperUser.build())
                .chain(stations -> {
                    if (stations.isEmpty()) {
                        return Uni.createFrom().item(List.of());
                    } else {
                        List<Uni<RadioStationStatusDTO>> statusUnis = stations.stream()
                                .map(station -> {
                                    return radioStationPool.get(station.getSlugName())
                                            .chain(onlineStation -> {
                                                //TODO fix the workaround
                                                String description = station.getDescription();
                                                if (onlineStation != null) {
                                                    if (onlineStation.getStatus() == RadioStationStatus.ON_LINE ||
                                                            onlineStation.getStatus() == RadioStationStatus.QUEUE_SATURATED ||
                                                            onlineStation.getStatus() == RadioStationStatus.IDLE ||
                                                            onlineStation.getStatus() == RadioStationStatus.WAITING_FOR_CURATOR
                                                    ){
                                                        onlineStation.setStatus(RadioStationStatus.ON_LINE);
                                                    }
                                                    return toStatusDTO(onlineStation);
                                                } else {
                                                    return Uni.createFrom().item(new RadioStationStatusDTO(
                                                            station.getLocalizedName().getOrDefault(CountryCode.valueOf(station.getCountry()).getPreferredLanguage(), station.getSlugName()),
                                                            station.getSlugName(),
                                                            null,
                                                            null,
                                                            null,
                                                            null,
                                                            RadioStationStatus.OFF_LINE.name(),
                                                            station.getCountry(),
                                                            station.getColor(),
                                                            description,
                                                            null
                                                    ));
                                                }
                                            })
                                            .onFailure().recoverWithItem(RadioStationStatusDTO::new);
                                })
                                .collect(Collectors.toList());
                        return Uni.join().all(statusUnis).andFailFast();
                    }
                });
    }

    public Uni<RadioStationStatusDTO> toStatusDTO(RadioStation radioStation) {
        if (radioStation == null) {
            return Uni.createFrom().nullItem();
        }

        String stationName = radioStation.getLocalizedName()
                .getOrDefault(radioStation.getCountry().getPreferredLanguage(), radioStation.getSlugName());
        String slugName = radioStation.getSlugName();
        String managedByType = radioStation.getManagedBy().toString();
        String currentStatus = radioStation.getStatus() != null ?
                radioStation.getStatus().name() : RadioStationStatus.OFF_LINE.name();
        String agentStatus = radioStation.getAiAgentStatus() != null ?
                radioStation.getAiAgentStatus().name() : AiAgentStatus.UNDEFINED.name();
        String stationCountryCode = radioStation.getCountry().name();

        if (radioStation.getAiAgentId() != null) {
            return aiAgentService.getById(radioStation.getAiAgentId(), SuperUser.build(), LanguageCode.en)
                    .onItem().transform(aiAgent -> new RadioStationStatusDTO(
                            stationName,
                            slugName,
                            managedByType,
                            aiAgent.getName(),
                            aiAgent.getPreferredLang().name().toUpperCase(),
                            agentStatus,
                            currentStatus,
                            stationCountryCode,
                            radioStation.getColor(),
                            radioStation.getDescription(),
                            animationService.generateRandomAnimation()
                    ))
                    .onFailure().recoverWithItem(() -> new RadioStationStatusDTO(
                            stationName,
                            slugName,
                            managedByType,
                            null,
                            null,
                            agentStatus,
                            currentStatus,
                            stationCountryCode,
                            radioStation.getColor(),
                            radioStation.getDescription(),
                            animationService.generateRandomAnimation()
                    ));
        }

        return Uni.createFrom().item(new RadioStationStatusDTO(
                stationName,
                slugName,
                managedByType,
                null,
                null,
                agentStatus,
                currentStatus,
                stationCountryCode,
                radioStation.getColor(),
                radioStation.getDescription(),
                animationService.generateRandomAnimation()
        ));
    }

    private Uni<List<RadioStation>> getOnlineStations() {
        Collection<RadioStation> onlineStationsSnapshot = radioStationPool.getOnlineStationsSnapshot();
        return Uni.createFrom().item(new ArrayList<>(onlineStationsSnapshot));
    }
}