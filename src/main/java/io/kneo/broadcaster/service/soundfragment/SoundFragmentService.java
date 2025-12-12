package io.kneo.broadcaster.service.soundfragment;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.dto.AudioMetadataDTO;
import io.kneo.broadcaster.dto.BrandSoundFragmentDTO;
import io.kneo.broadcaster.dto.SoundFragmentDTO;
import io.kneo.broadcaster.dto.UploadFileDTO;
import io.kneo.broadcaster.dto.filter.SoundFragmentFilterDTO;
import io.kneo.broadcaster.model.FileMetadata;
import io.kneo.broadcaster.model.brand.Brand;
import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.model.cnst.RatingAction;
import io.kneo.broadcaster.model.cnst.SourceType;
import io.kneo.broadcaster.model.soundfragment.BrandSoundFragment;
import io.kneo.broadcaster.model.soundfragment.SoundFragment;
import io.kneo.broadcaster.model.soundfragment.SoundFragmentFilter;
import io.kneo.broadcaster.repository.soundfragment.SoundFragmentRepository;
import io.kneo.broadcaster.service.BrandService;
import io.kneo.broadcaster.service.RefService;
import io.kneo.broadcaster.service.maintenance.LocalFileCleanupService;
import io.kneo.broadcaster.util.BrandLogger;
import io.kneo.broadcaster.util.FileSecurityUtils;
import io.kneo.core.dto.DocumentAccessDTO;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.IUser;
import io.kneo.core.model.user.SuperUser;
import io.kneo.core.service.AbstractService;
import io.kneo.core.service.UserService;
import io.kneo.core.util.WebHelper;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Validator;
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
    private final BrandService brandService;
    private final LocalFileCleanupService localFileCleanupService;
    private final RefService refService;
    private String uploadDir;
    Validator validator;

    protected SoundFragmentService(UserService userService) {
        super(userService);
        this.localFileCleanupService = null;
        this.repository = null;
        this.brandService = null;
        this.refService = null;
    }

    public Uni<List<BrandSoundFragmentDTO>> getBrandSoundFragmentsBySimilarity(String brandName, String keyword, int limit, int offset) {
        assert repository != null;
        assert brandService != null;

        return brandService.getBySlugName(brandName)
                .onItem().transformToUni(radioStation -> {
                    if (radioStation == null) {
                        return Uni.createFrom().failure(new IllegalArgumentException("Brand not found: " + brandName));
                    }
                    UUID brandId = radioStation.getId();
                    return repository.getForBrandBySimilarity(brandId, keyword, limit, offset, false, SuperUser.build())
                            .chain(fragments -> {
                                if (fragments.isEmpty()) {
                                    return Uni.createFrom().item(Collections.<BrandSoundFragmentDTO>emptyList());
                                }

                                List<Uni<BrandSoundFragmentDTO>> unis = fragments.stream()
                                        .map(this::mapToBrandSoundFragmentDTO)
                                        .collect(Collectors.toList());

                                return Uni.join().all(unis).andFailFast();
                            });
                })
                .onFailure().recoverWithUni(failure -> {
                    LOGGER.error("Failed to similarity-search fragments for brand: {}", brandName, failure);
                    return Uni.<List<BrandSoundFragmentDTO>>createFrom().failure(failure);
                });
    }

    @Inject
    public SoundFragmentService(UserService userService,
                                BrandService brandService,
                                LocalFileCleanupService localFileCleanupService,
                                Validator validator,
                                SoundFragmentRepository repository,
                                BroadcasterConfig config,
                                io.kneo.broadcaster.service.RefService refService) {
        super(userService);
        this.localFileCleanupService = localFileCleanupService;
        this.validator = validator;
        this.repository = repository;
        this.brandService = brandService;
        this.refService = refService;
        uploadDir = config.getPathUploads() + "/sound-fragments-controller";
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
        return repository.findById(uuid, user.getId(), false, true, false);
    }

    public Uni<SoundFragment> getById(UUID uuid) {
        assert repository != null;
        return repository.findById(uuid, SuperUser.ID, false, false, true);
    }

    public Uni<List<SoundFragment>> getByTypeAndBrand(PlaylistItemType type, UUID brandId) {
        assert repository != null;
        return repository.findByTypeAndBrand(type, brandId, 100, 0);
    }

    @Override
    public Uni<SoundFragmentDTO> getDTO(UUID uuid, IUser user, LanguageCode code) {
        assert repository != null;

        Uni<SoundFragment> soundFragmentUni = repository.findById(uuid, user.getId(), false, true, true);

        Uni<List<UUID>> brandsUni = repository.getBrandsForSoundFragment(uuid, user);

        return Uni.combine().all().unis(soundFragmentUni, brandsUni).asTuple()
                .chain(tuple -> {
                    SoundFragment doc = tuple.getItem1();
                    List<UUID> representedInBrands = tuple.getItem2();
                    return mapToDTO(doc, true, representedInBrands);
                });
    }

    public Uni<SoundFragmentDTO> getDTOTemplate(IUser user, LanguageCode code) {
        return brandService.getAll(10, 0, user)
                .onItem().transform(userRadioStations -> {
                    SoundFragmentDTO dto = new SoundFragmentDTO();
                    dto.setAuthor(user.getUserName());
                    dto.setLastModifier(user.getUserName());
                    dto.setNewlyUploaded(List.of());
                    dto.setUploadedFiles(List.of());
                    dto.setType(PlaylistItemType.SONG);

                    List<UUID> stationIds = userRadioStations.stream()
                            .map(Brand::getId)
                            .collect(Collectors.toList());
                    dto.setRepresentedInBrands(stationIds);

                    return dto;
                });
    }

    public Uni<FileMetadata> getFileBySlugName(UUID soundFragmentId, String slugName, IUser user) {
        assert repository != null;
        return repository.getFileBySlugName(soundFragmentId, slugName, user, false);
    }

    public Uni<List<BrandSoundFragmentDTO>> getBrandSoundFragments(String brandName, int limit, int offset, SoundFragmentFilterDTO filterDTO, IUser user) {
        assert repository != null;
        assert brandService != null;

        return brandService.getBySlugName(brandName)
                .onItem().transformToUni(radioStation -> {
                    if (radioStation == null) {
                        return Uni.createFrom().failure(new IllegalArgumentException("Brand not found: " + brandName));
                    }
                    SoundFragmentFilter filter = toFilter(filterDTO);
                    String filterStatus = (filter != null && filter.isActivated()) ? "active" : "none";
                    BrandLogger.logActivity(brandName, "brand_fragments_request",
                            "Requesting fragments (limit: %d, offset: %d, filter: %s) for user: %s",
                            limit, offset, filterStatus, user.getUserName());
                    UUID brandId = radioStation.getId();
                    return repository.getForBrand(brandId, limit, offset, false, user, filter)
                            .chain(fragments -> {
                                if (fragments.isEmpty()) {
                                    BrandLogger.logActivity(brandName, "no_brand_fragments",
                                            "No fragments found for this brand with applied filter");
                                    return Uni.createFrom().item(Collections.<BrandSoundFragmentDTO>emptyList());
                                }

                                BrandLogger.logActivity(brandName, "brand_fragments_retrieved",
                                        "Retrieved %d fragments", fragments.size());

                                List<Uni<BrandSoundFragmentDTO>> unis = fragments.stream()
                                        .map(this::mapToBrandSoundFragmentDTO)
                                        .collect(Collectors.toList());

                                return Uni.join().all(unis).andFailFast();
                            });
                })
                .onFailure().recoverWithUni(failure -> {
                    BrandLogger.logActivity(brandName, "brand_fragments_error",
                            "Failed to get fragments: %s", failure.getMessage());
                    LOGGER.error("Failed to get fragments for brand: {}", brandName, failure);
                    return Uni.<List<BrandSoundFragmentDTO>>createFrom().failure(failure);
                });
    }

    public Uni<Integer> getBrandSoundFragmentsCount(final String brand, final SoundFragmentFilterDTO filterDTO, IUser user) {
        assert repository != null;

        SoundFragmentFilter filter = toFilter(filterDTO);
        String filterStatus = (filter != null && filter.isActivated()) ? "active" : "none";

        BrandLogger.logActivity(brand, "count_request",
                "Requesting fragment count for brand with filter: %s for user: %s", filterStatus, user.getUserName());

        return brandService.getBySlugName(brand)
                .onItem().transformToUni(radioStation -> {
                    if (radioStation == null) {
                        BrandLogger.logActivity(brand, "brand_not_found",
                                "Brand not found in getCountBrandSoundFragments");
                        return Uni.createFrom().failure(new IllegalArgumentException("Brand not found: " + brand));
                    }
                    UUID brandId = radioStation.getId();
                    return repository.getForBrandCount(brandId, false, user, filter)
                            .invoke(count -> {
                                BrandLogger.logActivity(brand, "fragment_count",
                                        "Found %d fragments for this brand", count);
                            });
                })
                .onFailure().recoverWithUni(failure -> {
                    BrandLogger.logActivity(brand, "count_error",
                            "Failed to count fragments: %s", failure.getMessage());
                    LOGGER.error("Failed to get fragments count for brand: {}", brand, failure);
                    return Uni.createFrom().failure(failure);
                });
    }

    public Uni<Integer> getBrandSoundFragmentsCount(final String brand, final SoundFragmentFilterDTO filterDTO) {
        return getBrandSoundFragmentsCount(brand, filterDTO, SuperUser.build());
    }

    public Uni<SoundFragmentDTO> upsert(String id, SoundFragmentDTO dto, IUser user, LanguageCode code) {
        SoundFragment entity = buildEntity(dto);

        List<FileMetadata> fileMetadataList = new ArrayList<>();
        if (dto.getNewlyUploaded() != null && !dto.getNewlyUploaded().isEmpty()) {
            for (String fileName : dto.getNewlyUploaded()) {
                String safeFileName;
                try {
                    safeFileName = FileSecurityUtils.sanitizeFilename(fileName);
                } catch (SecurityException e) {
                    LOGGER.error("Security violation: Unsafe filename '{}' from user: {}", fileName, user.getUserName());
                    return Uni.createFrom().failure(new IllegalArgumentException("Invalid filename: " + fileName));
                }

                String tempFolderName = "";
                FileMetadata fileMetadata = new FileMetadata();
                if (id == null || "new".equalsIgnoreCase(id)) {
                    tempFolderName = "temp";
                } else {
                    try {
                        UUID.fromString(id);
                    } catch (IllegalArgumentException e) {
                        LOGGER.error("Security violation: Invalid entity ID '{}' from user: {}", id, user.getUserName());
                        return Uni.createFrom().failure(new IllegalArgumentException("Invalid entity ID"));
                    }
                }

                Path baseDir = Paths.get(uploadDir, user.getUserName(), tempFolderName);
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
                    return Uni.createFrom().failure(new IllegalArgumentException("Something happen wrong with the uploaded file"));
                   // continue;
                }

                fileMetadata.setFilePath(secureFilePath);
                fileMetadata.setFileOriginalName(safeFileName);
                fileMetadata.setSlugName(WebHelper.generateSlug(entity.getArtist(), entity.getTitle()));
                fileMetadataList.add(fileMetadata);
            }
        }

        entity.setFileMetadataList(fileMetadataList);

        if ("new".equalsIgnoreCase(id) || id == null) {
            entity.setSource(SourceType.USER_UPLOAD);
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
                                    repository.findById(documentId, user.getId(), false, false, false)
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

        List<Uni<Brand>> stationUnis = brandNames.stream()
                .map(brandService::getBySlugName)
                .collect(Collectors.toList());

        return Uni.join().all(stationUnis).andFailFast()
                .map(stations -> stations.stream()
                        .filter(Objects::nonNull)
                        .map(Brand::getId)
                        .collect(Collectors.toList()));
    }

    private Uni<SoundFragmentDTO> mapToDTO(SoundFragment doc, boolean exposeFileUrl, List<UUID> representedInBrands) {
        return Uni.combine().all().unis(
                userService.getUserName(doc.getAuthor()),
                userService.getUserName(doc.getLastModifier())
        ).asTuple().onItem().transform(tuple -> {
            String author = tuple.getItem1();
            String lastModifier = tuple.getItem2();
            List<UploadFileDTO> files = new ArrayList<>();

            if (exposeFileUrl && doc.getFileMetadataList() != null) {
                doc.getFileMetadataList().forEach(meta -> {
                    String safeFileName = FileSecurityUtils.sanitizeFilename(meta.getFileOriginalName());
                    UploadFileDTO fileDto = new UploadFileDTO();
                    fileDto.setId(meta.getSlugName());
                    fileDto.setName(safeFileName);
                    fileDto.setStatus("finished");
                    fileDto.setUrl("/soundfragments/files/" + doc.getId() + "/" + meta.getSlugName());
                    fileDto.setPercentage(100);
                    files.add(fileDto);
                });
            }

            SoundFragmentDTO dto = new SoundFragmentDTO();
            dto.setId(doc.getId());
            dto.setAuthor(author);
            dto.setRegDate(doc.getRegDate());
            dto.setLastModifier(lastModifier);
            dto.setLastModifiedDate(doc.getLastModifiedDate());
            dto.setSource(doc.getSource());
            dto.setStatus(doc.getStatus());
            dto.setType(doc.getType());
            dto.setTitle(doc.getTitle());
            dto.setArtist(doc.getArtist());
            dto.setGenres(doc.getGenres());
            dto.setLabels(doc.getLabels());
            dto.setAlbum(doc.getAlbum());
            dto.setLength(doc.getLength());
            dto.setDescription(doc.getDescription());
            dto.setUploadedFiles(files);
            dto.setRepresentedInBrands(representedInBrands);
            return dto;
        });
    }

    private SoundFragment buildEntity(SoundFragmentDTO dto) {
        SoundFragment doc = new SoundFragment();
        doc.setStatus(dto.getStatus());
        doc.setType(dto.getType());
        doc.setTitle(dto.getTitle());
        doc.setArtist(dto.getArtist());
        doc.setGenres(dto.getGenres());
        doc.setLabels(dto.getLabels());
        doc.setAlbum(dto.getAlbum());
        doc.setLength(dto.getLength());
        doc.setDescription(dto.getDescription());
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
                    dto.setRatedByBrandCount(doc.getRatedByBrandCount());
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

    private SoundFragmentFilter toFilter(SoundFragmentFilterDTO dto) {
        if (dto == null) {
            return null;
        }

        SoundFragmentFilter filter = new SoundFragmentFilter();
        filter.setActivated(dto.isActivated());
        filter.setGenre(dto.getGenres());
        filter.setLabels(dto.getLabels());
        filter.setSource(dto.getSources());
        filter.setType(dto.getTypes());
        filter.setSearchTerm(dto.getSearchTerm());

        return filter;
    }

    public Uni<UUID> resolveBrandSlug(String brandSlug) {
        if (brandSlug == null || brandSlug.trim().isEmpty()) {
            return Uni.createFrom().nullItem();
        }
        
        assert brandService != null;
        return brandService.getBySlugName(brandSlug)
                .map(station -> station != null ? station.getId() : null)
                .onFailure().recoverWithItem(err -> {
                    LOGGER.warn("Failed to resolve brandSlug: {}", brandSlug, err);
                    return null;
                });
    }

    public Uni<SoundFragment> createFromBulkUpload(UploadFileDTO uploadFile, UUID brandId, IUser user) {
        if (uploadFile.getMetadata() == null) {
            return Uni.createFrom().failure(new IllegalArgumentException("Upload file has no metadata"));
        }
        
        if (uploadFile.getFullPath() == null) {
            return Uni.createFrom().failure(new IllegalArgumentException("Upload file has no fullPath"));
        }

        AudioMetadataDTO metadata = uploadFile.getMetadata();
        
        SoundFragment fragment = new SoundFragment();
        fragment.setSource(SourceType.USER_UPLOAD);
        fragment.setStatus(1);
        fragment.setType(PlaylistItemType.SONG);
        fragment.setTitle(metadata.getTitle() != null ? metadata.getTitle() : uploadFile.getName());
        fragment.setArtist(metadata.getArtist() != null ? metadata.getArtist() : "Unknown Artist");
        fragment.setAlbum(metadata.getAlbum());
        fragment.setLength(metadata.getLength());
        fragment.setSlugName(WebHelper.generateSlug(fragment.getArtist(), fragment.getTitle()));
        
        FileMetadata fileMetadata = new FileMetadata();
        fileMetadata.setFilePath(Paths.get(uploadFile.getFullPath()));
        fragment.setFileMetadataList(List.of(fileMetadata));
        List<UUID> brandIds = brandId != null ? List.of(brandId) : List.of();
        
        assert refService != null;
        return refService.resolveGenresByName(metadata.getGenre())
                .chain(genreIds -> {
                    fragment.setGenres(genreIds);
                    assert repository != null;
                    return repository.insert(fragment, brandIds, user);
                });
    }

    public Uni<Integer> rateSoundFragmentByAction(String brandSlug, UUID soundFragmentId, RatingAction action, String previousAction, IUser user) {
        int delta;
        switch (action) {
            case LIKE:
                delta = 10;
                break;
            case DISLIKE:
                delta = -10;
                break;
            case CANCEL:
                if ("LIKE".equals(previousAction)) {
                    delta = -10;
                } else if ("DISLIKE".equals(previousAction)) {
                    delta = 10;
                } else {
                    delta = 0;
                }
                break;
            default:
                return Uni.createFrom().failure(new IllegalArgumentException("Invalid action: " + action));
        }

        return resolveBrandSlug(brandSlug)
                .chain(brandId -> {
                    if (brandId == null) {
                        return Uni.createFrom().failure(new IllegalArgumentException("Brand not found: " + brandSlug));
                    }
                    assert repository != null;
                    return repository.updateRatedByBrandCount(brandId, soundFragmentId, delta, user);
                });
    }
}