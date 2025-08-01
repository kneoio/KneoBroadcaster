package io.kneo.broadcaster.service;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.dto.BrandSoundFragmentDTO;
import io.kneo.broadcaster.dto.SoundFragmentDTO;
import io.kneo.broadcaster.dto.filter.SoundFragmentFilterDTO;
import io.kneo.broadcaster.dto.UploadFileDTO;
import io.kneo.broadcaster.model.BrandSoundFragment;
import io.kneo.broadcaster.model.FileMetadata;
import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.model.SoundFragment;
import io.kneo.broadcaster.model.SoundFragmentFilter;
import io.kneo.broadcaster.repository.SoundFragmentRepository;
import io.kneo.broadcaster.repository.file.DigitalOceanStorage;
import io.kneo.broadcaster.service.filemaintainance.LocalFileCleanupService;
import io.kneo.broadcaster.util.FileSecurityUtils;
import io.kneo.broadcaster.util.WebHelper;
import io.kneo.core.dto.DocumentAccessDTO;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.IUser;
import io.kneo.core.model.user.SuperUser;
import io.kneo.core.repository.UserRepository;
import io.kneo.core.service.AbstractService;
import io.kneo.core.service.UserService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Validator;
import io.kneo.broadcaster.util.BrandActivityLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class SoundFragmentService extends AbstractService<SoundFragment, SoundFragmentDTO> {
    private static final Logger LOGGER = LoggerFactory.getLogger(SoundFragmentService.class);

    private final SoundFragmentRepository repository;
    private final RadioStationService radioStationService;
    private final LocalFileCleanupService localFileCleanupService;
    private final TransactionCoordinatorService transactionCoordinator;
    private final FileOperationLockService lockService;
    private final DigitalOceanStorage digitalOceanStorage;
    private final BroadcasterConfig config;
    private String uploadDir;
    Validator validator;

    protected SoundFragmentService(TransactionCoordinatorService transactionCoordinator, FileOperationLockService lockService, DigitalOceanStorage digitalOceanStorage, BroadcasterConfig config) {
        super(null);
        this.transactionCoordinator = transactionCoordinator;
        this.lockService = lockService;
        this.digitalOceanStorage = digitalOceanStorage;
        this.localFileCleanupService = null;
        this.config = config;
        this.repository = null;
        this.radioStationService = null;
    }

    @Inject
    public SoundFragmentService(UserRepository userRepository,
                                UserService userService,
                                RadioStationService radioStationService,
                                LocalFileCleanupService localFileCleanupService, FileOperationLockService lockService,
                                Validator validator,
                                SoundFragmentRepository repository, TransactionCoordinatorService transactionCoordinator, DigitalOceanStorage digitalOceanStorage,
                                BroadcasterConfig config) {
        super(userRepository, userService);
        this.localFileCleanupService = localFileCleanupService;
        this.lockService = lockService;
        this.validator = validator;
        this.repository = repository;
        this.radioStationService = radioStationService;
        this.transactionCoordinator = transactionCoordinator;
        this.digitalOceanStorage = digitalOceanStorage;
        uploadDir = config.getPathUploads() + "/sound-fragments-controller";
        this.config = config;
    }

    public Uni<List<SoundFragmentDTO>> getAllDTO(final int limit, final int offset, final IUser user) {
        return getAllDTO(limit, offset, user, null);
    }

    public Uni<List<SoundFragmentDTO>> getAllDTO(final int limit, final int offset, final IUser user, final SoundFragmentFilterDTO filterDTO) {
        assert repository != null;
        SoundFragmentFilter filter = toFilter(filterDTO);
        return repository.getAll(limit, offset, false, user, filter)
                .chain(list -> {
                    if (list.isEmpty()) {
                        return Uni.createFrom().item(List.of());
                    } else {
                        List<Uni<SoundFragmentDTO>> unis = list.stream()
                                .map(doc -> mapToDTO(doc, false, null))
                                .collect(Collectors.toList());
                        return Uni.join().all(unis).andFailFast();
                    }
                });
    }

    public Uni<Integer> getAllCount(final IUser user) {
        return getAllCount(user, null);
    }

    public Uni<Integer> getAllCount(final IUser user, final SoundFragmentFilterDTO filterDTO) {
        assert repository != null;
        SoundFragmentFilter filter = toFilter(filterDTO);
        return repository.getAllCount(user, false, filter);
    }

    public Uni<List<SoundFragment>> getAll(final int limit, final int offset, final SoundFragmentFilterDTO filterDTO) {
        assert repository != null;
        SoundFragmentFilter filter = toFilter(filterDTO);
        return repository.getAll(limit, offset, false, SuperUser.build(), filter);
    }

    public Uni<List<SoundFragment>> getAll(final int limit, final int offset, IUser user) {
        return getAll(limit, offset, user, null);
    }

    public Uni<List<SoundFragment>> getAll(final int limit, final int offset, IUser user, final SoundFragmentFilterDTO filterDTO) {
        assert repository != null;
        SoundFragmentFilter filter = toFilter(filterDTO);
        return repository.getAll(limit, offset, false, user, filter);
    }

    public Uni<SoundFragment> getById(UUID uuid, IUser user) {
        assert repository != null;
        return repository.findById(uuid, user.getId(), false);
    }

    @Override
    public Uni<SoundFragmentDTO> getDTO(UUID uuid, IUser user, LanguageCode code) {
        assert repository != null;

        Uni<SoundFragment> soundFragmentUni = repository.findById(uuid, user.getId(), false);
        Uni<List<UUID>> brandsUni = repository.getBrandsForSoundFragment(uuid, user);

        return Uni.combine().all().unis(soundFragmentUni, brandsUni).asTuple()
                .chain(tuple -> {
                    SoundFragment doc = tuple.getItem1();
                    List<UUID> representedInBrands = tuple.getItem2();
                    return mapToDTO(doc, true, representedInBrands);
                });
    }

    public Uni<SoundFragmentDTO> getDTOTemplate(IUser user, LanguageCode code) {
        return radioStationService.getAll(10, 0, user)
                .onItem().transform(userRadioStations -> {
                    SoundFragmentDTO dto = new SoundFragmentDTO();
                    dto.setAuthor(user.getUserName());
                    dto.setLastModifier(user.getUserName());
                    dto.setNewlyUploaded(List.of());
                    dto.setUploadedFiles(List.of());

                    List<UUID> stationIds = userRadioStations.stream()
                            .map(RadioStation::getId)
                            .collect(Collectors.toList());
                    dto.setRepresentedInBrands(stationIds);

                    return dto;
                });
    }

    public Uni<FileMetadata> getFile(UUID soundFragmentId, String slugName, IUser user) {
        assert repository != null;
        return repository.getFileById(soundFragmentId, slugName, user, false);
    }

    public Uni<List<BrandSoundFragment>> getForBrand(String brandName, int quantity, boolean shuffle, IUser user) {
        return getForBrand(brandName, quantity, shuffle, user, null);
    }

    public Uni<List<BrandSoundFragment>> getForBrand(String brandName, int quantity, boolean shuffle, IUser user, SoundFragmentFilterDTO filterDTO) {
        assert repository != null;
        assert radioStationService != null;

        SoundFragmentFilter filter = toFilter(filterDTO);
        String filterStatus = (filter != null && filter.isActivated()) ? "active" : "none";

        BrandActivityLogger.logActivity(brandName, "fragment_request",
                "Requesting %d fragments, shuffle: %s, user: %s, filter: %s",
                quantity, shuffle, user.getUserName(), filterStatus);

        return radioStationService.findByBrandName(brandName)
                .onItem().transformToUni(radioStation -> {
                    if (radioStation == null) {
                        BrandActivityLogger.logActivity(brandName, "brand_not_found",
                                "Brand not found");
                        return Uni.createFrom().failure(new IllegalArgumentException("Brand not found: " + brandName));
                    }
                    UUID brandId = radioStation.getId();
                    int limit = quantity;
                    if (shuffle) {
                        limit = 50;
                    }
                    BrandActivityLogger.logActivity(brandName, "fetching_fragments",
                            "Fetching up to %d fragments for brand ID: %s with filter", limit, brandId);

                    return repository.findForBrand(brandId, limit, 0, false, user, filter)
                            .chain(fragments -> {
                                if (fragments == null || fragments.isEmpty()) {
                                    BrandActivityLogger.logActivity(brandName, "no_fragments_found",
                                            "No fragments available for this brand with applied filter");
                                } else {
                                    BrandActivityLogger.logActivity(brandName, "fragments_retrieved",
                                            "Retrieved %d fragments", fragments.size());

                                    if (shuffle) {
                                        BrandActivityLogger.logActivity(brandName, "shuffling_fragments",
                                                "Shuffling fragments");
                                        Collections.shuffle(fragments);
                                        if (quantity > 0 && fragments.size() > quantity) {
                                            fragments = fragments.subList(0, quantity);
                                            BrandActivityLogger.logActivity(brandName, "fragments_limited",
                                                    "Limited to %d fragments after shuffle", fragments.size());
                                        }
                                    }
                                }
                                return Uni.createFrom().item(fragments);
                            });
                })
                .onFailure().recoverWithUni(failure -> {
                    BrandActivityLogger.logActivity(brandName, "fragment_retrieval_error",
                            "Failed to get fragments: %s", failure.getMessage());
                    LOGGER.error("Failed to get fragments for brand: {}", brandName, failure);
                    return Uni.createFrom().failure(failure);
                });
    }

    public Uni<List<BrandSoundFragmentDTO>> getBrandSoundFragments(String brandName, int limit, int offset) {
        return getBrandSoundFragments(brandName, limit, offset, null);
    }

    public Uni<List<BrandSoundFragmentDTO>> getBrandSoundFragments(String brandName, int limit, int offset, SoundFragmentFilterDTO filterDTO) {
        assert repository != null;
        assert radioStationService != null;

        SoundFragmentFilter filter = toFilter(filterDTO);
        String filterStatus = (filter != null && filter.isActivated()) ? "active" : "none";

        BrandActivityLogger.logActivity(brandName, "brand_fragments_request",
                "Requesting fragments (limit: %d, offset: %d, filter: %s)",
                limit, offset, filterStatus);

        return radioStationService.findByBrandName(brandName)
                .onItem().transformToUni(radioStation -> {
                    if (radioStation == null) {
                        return Uni.createFrom().failure(new IllegalArgumentException("Brand not found: " + brandName));
                    }
                    UUID brandId = radioStation.getId();
                    BrandActivityLogger.logActivity(brandName, "fetching_brand_fragments",
                            "Fetching fragments for brand ID: %s (limit: %d, offset: %d) with filter",
                            brandId, limit, offset);

                    return repository.findForBrand(brandId, limit, offset, false, SuperUser.build(), filter)
                            .chain(fragments -> {
                                if (fragments.isEmpty()) {
                                    BrandActivityLogger.logActivity(brandName, "no_brand_fragments",
                                            "No fragments found for this brand with applied filter");
                                    return Uni.createFrom().item(Collections.<BrandSoundFragmentDTO>emptyList());
                                }

                                BrandActivityLogger.logActivity(brandName, "brand_fragments_retrieved",
                                        "Retrieved %d fragments", fragments.size());

                                List<Uni<BrandSoundFragmentDTO>> unis = fragments.stream()
                                        .map(this::mapToBrandSoundFragmentDTO)
                                        .collect(Collectors.toList());

                                return Uni.join().all(unis).andFailFast();
                            });
                })
                .onFailure().recoverWithUni(failure -> {
                    BrandActivityLogger.logActivity(brandName, "brand_fragments_error",
                            "Failed to get fragments: %s", failure.getMessage());
                    LOGGER.error("Failed to get fragments for brand: {}", brandName, failure);
                    return Uni.<List<BrandSoundFragmentDTO>>createFrom().failure(failure);
                });
    }

    public Uni<Integer> getCountBrandSoundFragments(final String brand, final IUser user) {
        return getCountBrandSoundFragments(brand, user, null);
    }

    public Uni<Integer> getCountBrandSoundFragments(final String brand, final IUser user, final SoundFragmentFilterDTO filterDTO) {
        assert repository != null;

        SoundFragmentFilter filter = toFilter(filterDTO);
        String filterStatus = (filter != null && filter.isActivated()) ? "active" : "none";

        BrandActivityLogger.logActivity(brand, "count_request",
                "Requesting fragment count for brand with filter: %s", filterStatus);

        return radioStationService.findByBrandName(brand)
                .onItem().transformToUni(radioStation -> {
                    if (radioStation == null) {
                        BrandActivityLogger.logActivity(brand, "brand_not_found",
                                "Brand not found in getCountBrandSoundFragments");
                        return Uni.createFrom().failure(new IllegalArgumentException("Brand not found: " + brand));
                    }
                    UUID brandId = radioStation.getId();
                    BrandActivityLogger.logActivity(brand, "counting_fragments",
                            "Counting fragments for brand ID: %s with filter", brandId);

                    return repository.findForBrandCount(brandId, false, user, filter)
                            .invoke(count -> {
                                BrandActivityLogger.logActivity(brand, "fragment_count",
                                        "Found %d fragments for this brand", count);
                            });
                })
                .onFailure().recoverWithUni(failure -> {
                    BrandActivityLogger.logActivity(brand, "count_error",
                            "Failed to count fragments: %s", failure.getMessage());
                    LOGGER.error("Failed to get fragments count for brand: {}", brand, failure);
                    return Uni.createFrom().failure(failure);
                });
    }

    public Uni<SoundFragmentDTO> upsert(String id, SoundFragmentDTO dto, IUser user, LanguageCode code) {
        SoundFragment entity = buildEntity(dto);

        List<FileMetadata> fileMetadataList = new ArrayList<>();
        if (dto.getNewlyUploaded() != null && !dto.getNewlyUploaded().isEmpty()) {
            for (String fileName : dto.getNewlyUploaded()) {

                String safeFileName;
                try {
                    //TODO do we really need the sanitization ?
                    safeFileName = FileSecurityUtils.sanitizeFilename(fileName);
                } catch (SecurityException e) {
                    LOGGER.error("Security violation: Unsafe filename '{}' from user: {}", fileName, user.getUserName());
                    return Uni.createFrom().failure(new IllegalArgumentException("Invalid filename: " + fileName));
                }

                FileMetadata fileMetadata = new FileMetadata();
                String entityId = id != null ? id : "temp";

                if (id != null) {
                    try {
                        UUID.fromString(id);
                    } catch (IllegalArgumentException e) {
                        LOGGER.error("Security violation: Invalid entity ID '{}' from user: {}", id, user.getUserName());
                        return Uni.createFrom().failure(new IllegalArgumentException("Invalid entity ID"));
                    }
                }
                Path baseDir = Paths.get(uploadDir, user.getUserName(), entityId);
                Path secureFilePath;
                try {
                    secureFilePath = FileSecurityUtils.secureResolve(baseDir, safeFileName);
                } catch (SecurityException e) {
                    LOGGER.error("Security violation: Path traversal attempt by user {} with filename {}",
                            user.getUserName(), fileName);
                    return Uni.createFrom().failure(new SecurityException("Invalid file path"));
                }

                if (!Files.exists(secureFilePath)) {
                    LOGGER.error("File not found at expected secure path: {} for user: {}", secureFilePath, user.getUserName());
                    continue;
                }

                fileMetadata.setFilePath(secureFilePath);
                fileMetadata.setFileOriginalName(safeFileName);
                fileMetadata.setSlugName(WebHelper.generateSlug(entity.getArtist(), entity.getTitle()));
                fileMetadataList.add(fileMetadata);
            }
        }

        entity.setFileMetadataList(fileMetadataList);

        if (id == null) {
            return repository.insert(entity, dto.getRepresentedInBrands(), user)
                    .chain(doc -> moveFilesForNewEntity(doc, fileMetadataList, user))
                    .chain(doc -> mapToDTO(doc, true, null))
                    .onFailure().invoke(failure -> {
                        LOGGER.warn("Entity creation failed, cleaning up temp files for user: {}", user.getUserName());
                        localFileCleanupService.cleanupTempFilesForUser(user.getUserName())
                                .subscribe().with(
                                        ignored -> LOGGER.debug("Temp files cleaned up after failure"),
                                        cleanupError -> LOGGER.warn("Failed to cleanup temp files", cleanupError)
                                );
                    });
        } else {
            return repository.update(UUID.fromString(id), entity, dto.getRepresentedInBrands(), user)
                    .chain(doc -> mapToDTO(doc, true, null))
                    .onFailure().invoke(failure -> {
                        LOGGER.warn("Entity update failed, cleaning up files for user: {}, entity: {}",
                                user.getUserName(), id);
                        localFileCleanupService.cleanupEntityFiles(user.getUserName(), id)
                                .subscribe().with(
                                        ignored -> LOGGER.debug("Entity files cleaned up after failure"),
                                        cleanupError -> LOGGER.warn("Failed to cleanup entity files", cleanupError)
                                );
                    });
        }
    }

    public Uni<List<SoundFragmentDTO>> search(String searchTerm, final int limit, final int offset, final IUser user) {
        return search(searchTerm, limit, offset, user, null);
    }

    public Uni<List<SoundFragmentDTO>> search(String searchTerm, final int limit, final int offset, final IUser user, final SoundFragmentFilterDTO filterDTO) {
        assert repository != null;
        SoundFragmentFilter filter = toFilter(filterDTO);
        return repository.search(searchTerm, limit, offset, false, user, filter)
                .chain(list -> {
                    if (list.isEmpty()) {
                        return Uni.createFrom().item(List.of());
                    } else {
                        List<Uni<SoundFragmentDTO>> unis = list.stream()
                                .map(doc -> mapToDTO(doc, false, null))
                                .collect(Collectors.toList());
                        return Uni.join().all(unis).andFailFast();
                    }
                });
    }

    public Uni<Integer> getSearchCount(String searchTerm, final IUser user) {
        return getSearchCount(searchTerm, user, null);
    }

    public Uni<Integer> getSearchCount(String searchTerm, final IUser user, final SoundFragmentFilterDTO filterDTO) {
        assert repository != null;
        SoundFragmentFilter filter = toFilter(filterDTO);
        return repository.getSearchCount(searchTerm, false, user, filter);
    }

    public Uni<LocalFileCleanupService.CleanupStats> getLocalFileCleanupStats() {
        return Uni.createFrom().item(localFileCleanupService.getStats());
    }

    private Uni<SoundFragment> moveFilesForNewEntity(SoundFragment doc, List<FileMetadata> fileMetadataList, IUser user) {
        if (fileMetadataList.isEmpty()) {
            return Uni.createFrom().item(doc);
        }

        try {
            Path userBaseDir = Paths.get(uploadDir, user.getUserName());
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

    public Uni<Integer> bulkBrandUpdate(List<UUID> documentIds, List<String> brands, String operation, IUser user) {
        return getBrandUUIDs(brands)
                .chain(brandUUIDs -> {
                    List<Uni<SoundFragment>> updateUnis = documentIds.stream()
                            .map(documentId ->
                                    repository.findById(documentId, user.getId(), false)
                                            .chain(fragment -> {
                                                if (fragment == null) {
                                                    return Uni.createFrom().nullItem();
                                                }

                                                List<UUID> updatedBrandIds;
                                                if ("SET".equals(operation)) {
                                                    updatedBrandIds = new ArrayList<>(brandUUIDs);
                                                } else {
                                                    updatedBrandIds = new ArrayList<>();
                                                }

                                                return repository.update(documentId, fragment, updatedBrandIds, user);
                                            })
                                            .onFailure().recoverWithItem(throwable -> {
                                                LOGGER.error("Failed to update document {}: {}", documentId, throwable.getMessage());
                                                return null;
                                            })
                            )
                            .collect(Collectors.toList());

                    return Uni.join().all(updateUnis).andFailFast()
                            .map(results -> (int) results.stream().filter(result -> result != null).count());
                });
    }

    private Uni<List<UUID>> getBrandUUIDs(List<String> brandNames) {
        if (brandNames == null || brandNames.isEmpty()) {
            return Uni.createFrom().item(new ArrayList<>());
        }

        List<Uni<RadioStation>> stationUnis = brandNames.stream()
                .map(radioStationService::findByBrandName)
                .collect(Collectors.toList());

        return Uni.join().all(stationUnis).andFailFast()
                .map(stations -> stations.stream()
                        .filter(Objects::nonNull)
                        .map(RadioStation::getId)
                        .collect(Collectors.toList()));
    }

    private Uni<SoundFragmentDTO> mapToDTO(SoundFragment doc, boolean exposeFileUrl, List<UUID> representedInBrands) {
        return Uni.combine().all().unis(
                userRepository.getUserName(doc.getAuthor()),
                userRepository.getUserName(doc.getLastModifier())
        ).asTuple().onItem().transform(tuple -> {
            String author = tuple.getItem1();
            String lastModifier = tuple.getItem2();
            List<UploadFileDTO> files = new ArrayList<>();

            if (exposeFileUrl && doc.getFileMetadataList() != null) {
                doc.getFileMetadataList()
                        .forEach(meta -> {
                            //TODO do we need sanitaztion here ?
                            String safeFileName = FileSecurityUtils.sanitizeFilename(meta.getFileOriginalName());
                            files.add(UploadFileDTO.builder()
                                    .id(meta.getSlugName())
                                    .name(safeFileName)
                                    .status("finished")
                                    .url("/soundfragments/files/" + doc.getId() + "/" + meta.getSlugName())
                                    .percentage(100)
                                    .build());
                        });
            }

            return SoundFragmentDTO.builder()
                    .id(doc.getId())
                    .author(author)
                    .regDate(doc.getRegDate())
                    .lastModifier(lastModifier)
                    .lastModifiedDate(doc.getLastModifiedDate())
                    .source(doc.getSource())
                    .status(doc.getStatus())
                    .type(doc.getType())
                    .title(doc.getTitle())
                    .artist(doc.getArtist())
                    .genre(doc.getGenre())
                    .album(doc.getAlbum())
                    .uploadedFiles(files)
                    .representedInBrands(representedInBrands)
                    .build();
        });
    }

    private SoundFragment buildEntity(SoundFragmentDTO dto) {
        SoundFragment doc = new SoundFragment();
        doc.setSource(dto.getSource());
        doc.setStatus(dto.getStatus());
        doc.setType(dto.getType());
        doc.setTitle(dto.getTitle());
        doc.setArtist(dto.getArtist());
        doc.setGenre(dto.getGenre());
        doc.setAlbum(dto.getAlbum());
        doc.setSlugName(WebHelper.generateSlug(dto.getTitle(), dto.getArtist()));
        return doc;
    }

    public Uni<Integer> delete(String id, IUser user) {
        assert repository != null;
        return repository.delete(UUID.fromString(id), user);
    }

    public Uni<Integer> archive(String id, IUser user) {
        assert repository != null;
        return repository.archive(UUID.fromString(id), user);
    }

    private Uni<BrandSoundFragmentDTO> mapToBrandSoundFragmentDTO(BrandSoundFragment doc) {
        return mapToDTO(doc.getSoundFragment(), false, null)
                .onItem().transform(soundFragmentDTO -> {
                    BrandSoundFragmentDTO dto = new BrandSoundFragmentDTO();
                    dto.setId(doc.getId());
                    dto.setSoundFragmentDTO(soundFragmentDTO);
                    dto.setPlayedByBrandCount(doc.getPlayedByBrandCount());
                    dto.setLastTimePlayedByBrand(doc.getPlayedTime());
                    dto.setDefaultBrandId(doc.getDefaultBrandId());
                    dto.setRepresentedInBrands(doc.getRepresentedInBrands());
                    return dto;
                });
    }

    public Uni<List<DocumentAccessDTO>> getDocumentAccess(UUID documentId, IUser user) {
        assert repository != null;
        return repository.getDocumentAccessInfo(documentId, user)
                .onItem().transform(accessInfoList ->
                        accessInfoList.stream()
                                .map(this::mapToDocumentAccessDTO)
                                .collect(Collectors.toList())
                );
    }

    /**
     * Converts SoundFragmentFilterDTO to SoundFragmentFilter domain model
     */
    private SoundFragmentFilter toFilter(SoundFragmentFilterDTO dto) {
        if (dto == null) {
            return null;
        }

        SoundFragmentFilter filter = new SoundFragmentFilter();
        filter.setActivated(dto.isActivated());
        filter.setGenres(dto.getGenres());
        filter.setSources(dto.getSources());
        filter.setTypes(dto.getTypes());

        return filter;
    }

}