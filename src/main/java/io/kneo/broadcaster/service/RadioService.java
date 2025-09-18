package io.kneo.broadcaster.service;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.dto.RadioStationDTO;
import io.kneo.broadcaster.dto.RadioStationStatusDTO;
import io.kneo.broadcaster.dto.SubmissionDTO;
import io.kneo.broadcaster.dto.cnst.AiAgentStatus;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.model.FileMetadata;
import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.model.SoundFragment;
import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.model.cnst.SourceType;
import io.kneo.broadcaster.repository.ContributionRepository;
import io.kneo.broadcaster.repository.soundfragment.SoundFragmentRepository;
import io.kneo.broadcaster.service.exceptions.FileUploadException;
import io.kneo.broadcaster.service.exceptions.RadioStationException;
import io.kneo.broadcaster.service.maintenance.LocalFileCleanupService;
import io.kneo.broadcaster.service.soundfragment.SoundFragmentService;
import io.kneo.broadcaster.service.stream.IStreamManager;
import io.kneo.broadcaster.service.stream.RadioStationPool;
import io.kneo.broadcaster.util.FileSecurityUtils;
import io.kneo.broadcaster.util.WebHelper;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.AnonymousUser;
import io.kneo.core.model.user.IUser;
import io.kneo.core.model.user.SuperUser;
import io.kneo.officeframe.cnst.CountryCode;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
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
    AiAgentService aiAgentService;

    @Inject
    RadioStationService radioStationService;

    @Inject
    AnimationService animationService;

    //TODO shoud use servcie
    @Inject
    private SoundFragmentRepository soundFragmentRepository;

    @Inject
    SoundFragmentService soundFragmentService;

    @Inject
    private ContributionRepository contributionRepository;

    @Inject
    private LocalFileCleanupService localFileCleanupService;

    @Inject
    private BroadcasterConfig config;

    @Inject
    MemoryService memoryService;

    private static final List<String> FEATURED_STATIONS = List.of("bratan","aye-ayes-ear","lumisonic", "voltage-georgia");

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

    public Uni<IStreamManager> getPlaylist(String brand) {
        return radioStationPool.get(brand)
                .onItem().ifNull().failWith(() ->
                        new RadioStationException(RadioStationException.ErrorType.STATION_NOT_ACTIVE)
                )
                .onItem().transform(RadioStation::getStreamManager)
                .onItem().ifNull().failWith(() ->
                        new RadioStationException(RadioStationException.ErrorType.PLAYLIST_NOT_AVAILABLE)
                );
    }

    public Uni<RadioStationStatusDTO> getStatus(String brand) {
        return getPlaylist(brand)
                .onItem().transform(IStreamManager::getRadioStation)
                .chain(v -> toStatusDTO(v, true));
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
                    .map(v -> toStatusDTO(v, false))
                    .collect(Collectors.toList());

            List<Uni<RadioStationStatusDTO>> offlineStatusUnis = allStations.stream()
                    .filter(station -> !onlineBrands.contains(station.getSlugName()))
                    .map(v -> toStatusDTO(v, false))
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
                        List<RadioStationDTO> stationsToProcess = stations.stream()
                                .filter(station -> FEATURED_STATIONS.contains(station.getSlugName()))
                                .toList();

                        List<Uni<RadioStationStatusDTO>> statusUnis = stationsToProcess.stream()
                                .map(station -> {
                                    return radioStationPool.get(station.getSlugName())
                                            .chain(onlineStation -> {
                                                String description = station.getDescription();
                                                if (onlineStation != null) {
                                                    if (onlineStation.getStatus() == RadioStationStatus.ON_LINE ||
                                                            onlineStation.getStatus() == RadioStationStatus.QUEUE_SATURATED ||
                                                            onlineStation.getStatus() == RadioStationStatus.IDLE ||
                                                            onlineStation.getStatus() == RadioStationStatus.WAITING_FOR_CURATOR
                                                    ) {
                                                        onlineStation.setStatus(RadioStationStatus.ON_LINE);
                                                    }
                                                    return toStatusDTO(onlineStation, false);
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
                                                            0,
                                                            station.getSubmissionPolicy(),
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

    public Uni<RadioStationStatusDTO> getStation(String slugName) {
        return radioStationService.getBySlugName(slugName)
                .chain(station -> {
                    if (station == null) {
                        return Uni.createFrom().nullItem();
                    }

                    return Uni.combine().all().unis(
                                    radioStationPool.get(station.getSlugName()),
                                    soundFragmentService.getBrandSoundFragmentsCount(slugName, null)
                            ).asTuple().chain(tuple -> {
                                RadioStation onlineStation = tuple.getItem1();
                                Integer availableSongs = tuple.getItem2();

                                RadioStation stationToUse = onlineStation != null ? onlineStation : station;
                                String currentStatus = onlineStation != null ?
                                        (onlineStation.getStatus() != null ? onlineStation.getStatus().name() : RadioStationStatus.OFF_LINE.name()) :
                                        RadioStationStatus.OFF_LINE.name();

                                return toStatusDTO(stationToUse, false)
                                        .onItem().transform(dto -> {
                                            dto.setCurrentStatus(currentStatus);
                                            dto.setAvailableSongs(availableSongs != null ? availableSongs : 0);
                                            dto.setDescription(station.getDescription());
                                            return dto;
                                        });
                            })
                            .onFailure().recoverWithItem(RadioStationStatusDTO::new);
                });
    }

    public Uni<SubmissionDTO> submit(String brand, SubmissionDTO dto) {
        return radioStationService.getBySlugName(brand)
                .chain(radioStation -> {
                    SoundFragment entity = buildEntity(dto);

                    List<FileMetadata> fileMetadataList = new ArrayList<>();
                    if (dto.getNewlyUploaded() != null && !dto.getNewlyUploaded().isEmpty()) {
                        for (String fileName : dto.getNewlyUploaded()) {
                            String safeFileName;
                            try {
                                safeFileName = FileSecurityUtils.sanitizeFilename(fileName);
                            } catch (SecurityException e) {
                                LOGGER.error("Security violation: Unsafe filename '{}' from user: {}", fileName, dto.getEmail());
                                return Uni.createFrom().failure(new IllegalArgumentException("Invalid filename: " + fileName));
                            }

                            FileMetadata fileMetadata = new FileMetadata();
                            String entityId = "temp";

                            Path baseDir = Paths.get(config.getPathUploads() + "/radio-controller", AnonymousUser.USER_NAME, entityId);
                            Path secureFilePath;
                            try {
                                secureFilePath = FileSecurityUtils.secureResolve(baseDir, safeFileName);
                            } catch (SecurityException e) {
                                LOGGER.error("Security violation: Path traversal attempt by user {} with filename {}",
                                        dto.getEmail(), fileName);
                                return Uni.createFrom().failure(new SecurityException("Invalid file path"));
                            }

                            if (!Files.exists(secureFilePath)) {
                                LOGGER.error("Submitted file not found at expected path: {} for user: {}", secureFilePath, dto.getEmail());
                                continue;
                            }

                            fileMetadata.setFilePath(secureFilePath);
                            fileMetadata.setFileOriginalName(safeFileName);
                            fileMetadata.setSlugName(WebHelper.generateSlug(entity.getArtist(), entity.getTitle()));
                            fileMetadataList.add(fileMetadata);
                        }
                    }

                    if (fileMetadataList.isEmpty()) {
                        return Uni.createFrom().failure(new FileUploadException("At least one file must be uploaded"));
                    }

                    entity.setFileMetadataList(fileMetadataList);

                    return soundFragmentRepository.insert(entity, List.of(radioStation.getId()), AnonymousUser.build())
                            .chain(doc -> moveFilesForNewEntity(doc, fileMetadataList, AnonymousUser.build())
                                    .chain(moved -> createContributionAndAgreement(moved, dto)
                                            .replaceWith(moved)))
                            .chain(doc -> {
                                String messageText = dto.getAttachedMessage();
                                if (messageText != null && !messageText.trim().isEmpty()) {
                                    return memoryService.addMessage(brand, dto.getEmail(), dto.getAttachedMessage())
                                            .replaceWith(doc)
                                            .onFailure().invoke(failure ->
                                                    LOGGER.warn("Failed to add message to memory for brand {}: {}", brand, failure.getMessage()));
                                }
                                return Uni.createFrom().item(doc);
                            })
                            .chain(this::mapToDTO)
                            .onFailure().invoke(failure -> {
                                LOGGER.warn("Entity creation failed, cleaning up temp files for user: {}", dto.getEmail());
                                localFileCleanupService.cleanupTempFilesForUser(AnonymousUser.USER_NAME)
                                        .subscribe().with(
                                                ignored -> LOGGER.debug("Temp files cleaned up after failure"),
                                                cleanupError -> LOGGER.warn("Failed to cleanup temp files", cleanupError)
                                        );
                            });
                });
    }

    private Uni<Void> createContributionAndAgreement(SoundFragment doc, SubmissionDTO dto) {
        Long userId = AnonymousUser.build().getId();
        return contributionRepository.insertContributionAndAgreementTx(
                doc.getId(),
                dto.getEmail(),
                dto.getAttachedMessage(),
                dto.isShareable(),
                dto.getEmail(),
                dto.getCountry() != CountryCode.UNKNOWN ? dto.getCountry().name() : null,
                dto.getIpAddress(),
                dto.getUserAgent(),
                dto.getAgreementVersion(),
                dto.getTermsText(),
                userId
        );
    }

    private SoundFragment buildEntity(SubmissionDTO dto) {
        SoundFragment doc = new SoundFragment();
        doc.setSource(SourceType.SUBMISSION);
        doc.setStatus(50);
        doc.setType(PlaylistItemType.SONG);
        doc.setTitle(dto.getTitle());
        doc.setArtist(dto.getArtist());
        doc.setGenres(dto.getGenres());
        doc.setAlbum(dto.getAlbum());
        doc.setDescription(dto.getDescription());
        doc.setSlugName(WebHelper.generateSlug(dto.getTitle(), dto.getArtist()));
        return doc;
    }

    private Uni<SoundFragment> moveFilesForNewEntity(SoundFragment doc, List<FileMetadata> fileMetadataList, IUser user) {
        if (fileMetadataList.isEmpty()) {
            return Uni.createFrom().item(doc);
        }

        try {
            Path userBaseDir = Paths.get(config.getPathUploads() + "/sound-fragments-controller", user.getUserName());
            Path tempDir = userBaseDir.resolve("temp");
            Path entityDir = userBaseDir.resolve(doc.getId().toString());

            if (Files.exists(tempDir)) {
                if (!Files.exists(entityDir)) {
                    Files.createDirectories(entityDir);
                }

                for (FileMetadata meta : fileMetadataList) {
                    String safeFileName = FileSecurityUtils.sanitizeFilename(meta.getFileOriginalName());

                    Path tempFile = FileSecurityUtils.secureResolve(tempDir, safeFileName);
                    Path entityFile = FileSecurityUtils.secureResolve(entityDir, safeFileName);

                    if (!FileSecurityUtils.isPathWithinBase(tempDir, tempFile) ||
                            !FileSecurityUtils.isPathWithinBase(entityDir, entityFile)) {
                        LOGGER.error("Security violation: Invalid file paths during move operation for user: {}", user.getUserName());
                        return Uni.createFrom().failure(new SecurityException("Invalid file paths"));
                    }

                    if (Files.exists(tempFile)) {
                        Files.move(tempFile, entityFile, StandardCopyOption.REPLACE_EXISTING);
                        meta.setFilePath(entityFile);
                        LOGGER.debug("Securely moved file from {} to {} for user: {}", tempFile, entityFile, user.getUserName());
                    }
                }
            }

            return Uni.createFrom().item(doc);
        } catch (SecurityException e) {
            LOGGER.error("Security violation during file move for entity: {}, user: {}", doc.getId(), user.getUserName(), e);
            return Uni.createFrom().failure(e);
        } catch (Exception e) {
            LOGGER.error("Failed to move files for entity: {}, user: {}", doc.getId(), user.getUserName(), e);
            return Uni.createFrom().failure(e);
        }
    }

    private Uni<SubmissionDTO> mapToDTO(SoundFragment doc) {
        return Uni.createFrom().item(
                SubmissionDTO.builder()
                        .title(doc.getTitle())
                        .artist(doc.getArtist())
                        .genres(doc.getGenres())
                        .album(doc.getAlbum())
                        .build());
    }

    public Uni<RadioStationStatusDTO> toStatusDTO(RadioStation radioStation, boolean includeAnimation) {
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
                            0,
                            radioStation.getSubmissionPolicy(),
                            includeAnimation ? animationService.generateRandomAnimation() : null
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
                            0,
                            radioStation.getSubmissionPolicy(),
                            includeAnimation ? animationService.generateRandomAnimation() : null
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
                0,
                radioStation.getSubmissionPolicy(),
                includeAnimation ? animationService.generateRandomAnimation() : null
        ));
    }

    private Uni<List<RadioStation>> getOnlineStations() {
        Collection<RadioStation> onlineStationsSnapshot = radioStationPool.getOnlineStationsSnapshot();
        return Uni.createFrom().item(new ArrayList<>(onlineStationsSnapshot));
    }
}