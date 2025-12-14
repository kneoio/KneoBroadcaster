package io.kneo.broadcaster.service;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.dto.ListenerDTO;
import io.kneo.broadcaster.dto.cnst.AiAgentStatus;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.dto.radio.SubmissionDTO;
import io.kneo.broadcaster.dto.radiostation.RadioStationStatusDTO;
import io.kneo.broadcaster.model.FileMetadata;
import io.kneo.broadcaster.model.aiagent.LanguagePreference;
import io.kneo.broadcaster.model.brand.AiOverriding;
import io.kneo.broadcaster.model.brand.Brand;
import io.kneo.broadcaster.model.cnst.ListenerType;
import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.model.cnst.RatingAction;
import io.kneo.broadcaster.model.cnst.SourceType;
import io.kneo.broadcaster.model.soundfragment.SoundFragment;
import io.kneo.broadcaster.model.stream.IStream;
import io.kneo.broadcaster.model.stream.OneTimeStream;
import io.kneo.broadcaster.repository.ContributionRepository;
import io.kneo.broadcaster.repository.soundfragment.SoundFragmentRepository;
import io.kneo.broadcaster.service.exceptions.FileUploadException;
import io.kneo.broadcaster.service.exceptions.RadioStationException;
import io.kneo.broadcaster.service.maintenance.LocalFileCleanupService;
import io.kneo.broadcaster.service.soundfragment.SoundFragmentService;
import io.kneo.broadcaster.service.stream.IStreamManager;
import io.kneo.broadcaster.service.stream.RadioStationPool;
import io.kneo.broadcaster.service.util.AnimationService;
import io.kneo.broadcaster.service.util.GeolocationService;
import io.kneo.broadcaster.util.FileSecurityUtils;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.AnonymousUser;
import io.kneo.core.model.user.IUser;
import io.kneo.core.model.user.SuperUser;
import io.kneo.core.repository.exception.ext.UserAlreadyExistsException;
import io.kneo.core.service.UserService;
import io.kneo.core.util.WebHelper;
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
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class RadioService {
    private static final Logger LOGGER = LoggerFactory.getLogger(RadioService.class);
    private final Random random = new Random();

    @Inject
    RadioStationPool radioStationPool;

    @Inject
    AiAgentService aiAgentService;

    @Inject
    BrandService brandService;

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
    ListenerService listenerService;

    @Inject
    UserService userService;

    private static final List<String> FEATURED_STATIONS = List.of("sacana","bratan","aye-ayes-ear","lumisonic", "v-o-i-d", "malucra");

    public Uni<IStream> initializeOneTimeStream(OneTimeStream oneTimeStream) {
        String streamSlugName = oneTimeStream.getSlugName();
        return radioStationPool.initializeStream(oneTimeStream)
                .onFailure().invoke(failure -> {
                    LOGGER.error("Failed to initialize stream: {}", streamSlugName, failure);
                    radioStationPool.get(streamSlugName)
                            .subscribe().with(
                                    station -> {
                                        if (station != null) {
                                            station.setStatus(RadioStationStatus.SYSTEM_ERROR);
                                            LOGGER.warn("Stream {} status set to SYSTEM_ERROR due to initialization failure", streamSlugName);
                                        }
                                    },
                                    error -> LOGGER.error("Failed to get station {} to set error status: {}", streamSlugName, error.getMessage(), error)
                            );
                });
    }

    public Uni<IStream> initializeStation(String brand) {
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

    public Uni<IStream> stopStation(String brand) {
        LOGGER.info("Stop brand: {}", brand);
        return radioStationPool.stopAndRemove(brand)
                .onFailure().invoke(failure ->
                        LOGGER.error("Failed to stop station for brand: {}", brand, failure)
                );
    }

    public Uni<IStreamManager> getStreamManager(String brand) {
        return radioStationPool.get(brand)
                .onItem().ifNull().failWith(() ->
                        new RadioStationException(RadioStationException.ErrorType.STATION_NOT_ACTIVE)
                )
                .onItem().transform(IStream::getStreamManager)
                .onItem().ifNull().failWith(() ->
                        new RadioStationException(RadioStationException.ErrorType.PLAYLIST_NOT_AVAILABLE)
                );
    }

    public Uni<RadioStationStatusDTO> getStatus(String brand) {
        return getStreamManager(brand)
                .onItem().transform(IStreamManager::getStream)
                .chain(v -> toStatusDTO(v, true));
    }

    public Uni<List<RadioStationStatusDTO>> getStations() {
        return Uni.combine().all().unis(
                getOnlineStations(),
                brandService.getAll(1000, 0)
        ).asTuple().chain(tuple -> {
            List<IStream> onlineStations = tuple.getItem1();
            List<Brand> allStations = tuple.getItem2();

            List<String> onlineBrands = onlineStations.stream()
                    .map(IStream::getSlugName)
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

    public Uni<List<RadioStationStatusDTO>> getAllStations(Boolean onlineOnly) {
        return Uni.combine().all().unis(
                getOnlineStations(),
                brandService.getAll(1000, 0)
        ).asTuple().chain(tuple -> {
            List<IStream> onlineStations = tuple.getItem1();
            List<Brand> allStations = tuple.getItem2();

            List<String> onlineBrands = onlineStations.stream()
                    .map(IStream::getSlugName)
                    .toList();

            if (onlineOnly != null && onlineOnly) {
                List<Uni<RadioStationStatusDTO>> onlineStatusUnis = onlineStations.stream()
                        .map(v -> toStatusDTO(v, false))
                        .collect(Collectors.toList());

                Uni<List<RadioStationStatusDTO>> result = onlineStatusUnis.isEmpty()
                        ? Uni.createFrom().item(List.of())
                        : Uni.join().all(onlineStatusUnis).andFailFast();
                return result;
            } else {
                List<Uni<RadioStationStatusDTO>> allStatusUnis = allStations.stream()
                        .map(station -> {
                            IStream onlineStation = onlineStations.stream()
                                    .filter(os -> os.getSlugName().equals(station.getSlugName()))
                                    .findFirst()
                                    .orElse(null);
                            return toStatusDTO(onlineStation != null ? onlineStation : station, false);
                        })
                        .collect(Collectors.toList());

                Uni<List<RadioStationStatusDTO>> result = allStatusUnis.isEmpty()
                        ? Uni.createFrom().item(List.of())
                        : Uni.join().all(allStatusUnis).andFailFast();
                return result;
            }
        }).onFailure().invoke(failure ->
                LOGGER.error("Failed to get all stations", failure)
        );
    }

    public Uni<RadioStationStatusDTO> getStation(String slugName) {
        return brandService.getBySlugName(slugName)
                .chain(station -> {
                    if (station == null) {
                        return Uni.createFrom().nullItem();
                    }

                    return Uni.combine().all().unis(
                                    radioStationPool.get(station.getSlugName()),
                                    soundFragmentService.getBrandSoundFragmentsCount(slugName, null)
                            ).asTuple().chain(tuple -> {
                                IStream onlineStation = tuple.getItem1();
                                Integer availableSongs = tuple.getItem2();

                                IStream stationToUse = onlineStation != null ? onlineStation : station;
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

    public Uni<SubmissionDTO> submit(String brand, SubmissionDTO dto, String ipHeader, String userAgent) {
        return brandService.getBySlugName(brand)
                .chain(radioStation -> registerContributor(dto.getEmail(), brand)
                        .chain(contributorUser -> {
                            String[] ipCountry = GeolocationService.parseIPHeader(ipHeader);
                            dto.setIpAddress(ipCountry[0]);
                            dto.setCountry(CountryCode.valueOf(ipCountry[1]));
                            dto.setUserAgent(userAgent);
                            return processSubmission(radioStation, dto, contributorUser);
                        }));
    }

    private Uni<IUser> registerContributor(String email, String stationSlug) {
        ListenerDTO dto = new ListenerDTO();
        dto.setEmail(email);
        dto.setListenerType(String.valueOf(ListenerType.CONTRIBUTOR));
        dto.getLocalizedName().put(LanguageCode.en, email);

        return listenerService.upsertWithStationSlug(null, dto, stationSlug, ListenerType.CONTRIBUTOR, SuperUser.build())
                .onFailure(UserAlreadyExistsException.class).recoverWithUni(throwable -> {
                    String slugName = WebHelper.generateSlug(email);
                    return userService.findByLogin(slugName)
                            .onItem().transformToUni(existingUser -> {
                                if (existingUser.getId() == 0) {
                                    return Uni.createFrom().failure(throwable);
                                }
                                ListenerDTO existingDto = new ListenerDTO();
                                existingDto.setUserId(existingUser.getId());
                                existingDto.setSlugName(slugName);
                                return Uni.createFrom().item(existingDto);
                            });
                })
                .onItem().transformToUni(listenerDTO ->
                        userService.findById(listenerDTO.getUserId())
                                .onItem().transform(userOptional -> userOptional.orElse(AnonymousUser.build()))
                );
    }

    private Uni<SubmissionDTO> processSubmission(Brand brand, SubmissionDTO dto, IUser user) {
                    SoundFragment entity = buildEntity(dto);
                    entity.setSource(SourceType.CONTRIBUTION);
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

                    return soundFragmentRepository.insert(entity, List.of(brand.getId()), user)
                            .chain(doc -> moveFilesForNewEntity(doc, fileMetadataList, user)
                                    .chain(moved -> createContributionAndAgreement(moved, dto)
                                            .replaceWith(moved)))
                            .chain(doc -> {
                                String messageText = dto.getAttachedMessage();
                                return Uni.createFrom().item(doc);
                            })
                            .chain(this::mapToDTO)
                            .onFailure().invoke(failure -> {
                                LOGGER.warn("Entity creation failed, cleaning up temp files for user: {}", dto.getEmail());
                                localFileCleanupService.cleanupTempFilesForUser(user.getUserName())
                                        .subscribe().with(
                                                ignored -> LOGGER.debug("Temp files cleaned up after failure"),
                                                cleanupError -> LOGGER.warn("Failed to cleanup temp files", cleanupError)
                                        );
                            });
    }


    private Uni<Void> createContributionAndAgreement(SoundFragment doc, SubmissionDTO dto) {
        Long userId = doc.getAuthor();
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
        doc.setSource(SourceType.CONTRIBUTION);
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

    public Uni<RadioStationStatusDTO> toStatusDTO(IStream brand, boolean includeAnimation) {
        if (brand == null) {
            return Uni.createFrom().nullItem();
        }

        String stationName = brand.getLocalizedName()
                .getOrDefault(brand.getCountry().getPreferredLanguage(), brand.getSlugName());
        String slugName = brand.getSlugName();
        String managedByType = brand.getManagedBy().toString();
        String currentStatus = brand.getStatus() != null ?
                brand.getStatus().name() : RadioStationStatus.OFF_LINE.name();
        String agentStatus = brand.getAiAgentStatus() != null ?
                brand.getAiAgentStatus().name() : AiAgentStatus.UNDEFINED.name();
        String stationCountryCode = brand.getCountry().name();

        if (brand.getAiAgentId() != null) {
            return aiAgentService.getById(brand.getAiAgentId(), SuperUser.build(), LanguageCode.en)
                    .onItem().transform(aiAgent -> {
                        LanguageCode selectedLang = selectLanguageByWeight(aiAgent);
                        AiOverriding overriddenAiDj = brand.getAiOverriding();
                        String djName = (overriddenAiDj != null && overriddenAiDj.getName() != null)
                                ? overriddenAiDj.getName()
                                : aiAgent.getName();
                        return new RadioStationStatusDTO(
                                stationName,
                                slugName,
                                managedByType,
                                djName,
                                selectedLang.name().toUpperCase(),
                                agentStatus,
                                currentStatus,
                                stationCountryCode,
                                brand.getColor(),
                                brand.getDescription(),
                                0,
                                brand.getSubmissionPolicy(),
                                brand.getMessagingPolicy(),
                                includeAnimation ? animationService.generateRandomAnimation() : null
                        );
                    })
                    .onFailure().recoverWithItem(() -> new RadioStationStatusDTO(
                            stationName,
                            slugName,
                            managedByType,
                            null,
                            null,
                            agentStatus,
                            currentStatus,
                            stationCountryCode,
                            brand.getColor(),
                            brand.getDescription(),
                            0,
                            brand.getSubmissionPolicy(),
                            brand.getMessagingPolicy(),
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
                brand.getColor(),
                brand.getDescription(),
                0,
                brand.getSubmissionPolicy(),
                brand.getMessagingPolicy(),
                includeAnimation ? animationService.generateRandomAnimation() : null
        ));
    }

    public Uni<Integer> rateSoundFragmentByAction(String brand, UUID fragmentId, RatingAction action, String previousAction) {
        return soundFragmentService.rateSoundFragmentByAction(brand, fragmentId, action, previousAction, SuperUser.build());
    }

    private Uni<List<IStream>> getOnlineStations() {
        Collection<IStream> onlineStationsSnapshot = radioStationPool.getOnlineStationsSnapshot();
        return Uni.createFrom().item(new ArrayList<>(onlineStationsSnapshot));
    }

    private LanguageCode selectLanguageByWeight(io.kneo.broadcaster.model.aiagent.AiAgent agent) {
        List<LanguagePreference> preferences = agent.getPreferredLang();
        if (preferences == null || preferences.isEmpty()) {
            LOGGER.warn("Agent '{}' has no language preferences, defaulting to English", agent.getName());
            return LanguageCode.en;
        }

        if (preferences.size() == 1) {
            return preferences.get(0).getCode();
        }

        double totalWeight = preferences.stream()
                .mapToDouble(LanguagePreference::getWeight)
                .sum();

        if (totalWeight <= 0) {
            LOGGER.warn("Agent '{}' has invalid weights (total <= 0), using first language", agent.getName());
            return preferences.get(0).getCode();
        }

        double randomValue = random.nextDouble() * totalWeight;
        double cumulativeWeight = 0;
        for (LanguagePreference pref : preferences) {
            cumulativeWeight += pref.getWeight();
            if (randomValue <= cumulativeWeight) {
                return pref.getCode();
            }
        }

        return preferences.get(0).getCode();
    }
}