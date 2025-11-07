package io.kneo.broadcaster.service.soundfragment;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.dto.BrandSoundFragmentDTO;
import io.kneo.broadcaster.dto.SoundFragmentDTO;
import io.kneo.broadcaster.dto.UploadFileDTO;
import io.kneo.broadcaster.dto.filter.SoundFragmentFilterDTO;
import io.kneo.broadcaster.model.FileMetadata;
import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.model.cnst.SourceType;
import io.kneo.broadcaster.model.radiostation.RadioStation;
import io.kneo.broadcaster.model.soundfragment.BrandSoundFragment;
import io.kneo.broadcaster.model.soundfragment.SoundFragment;
import io.kneo.broadcaster.model.soundfragment.SoundFragmentFilter;
import io.kneo.broadcaster.repository.soundfragment.SoundFragmentRepository;
import io.kneo.broadcaster.service.RadioStationService;
import io.kneo.broadcaster.service.maintenance.LocalFileCleanupService;
import io.kneo.broadcaster.util.BrandActivityLogger;
import io.kneo.broadcaster.util.FileSecurityUtils;
import io.kneo.broadcaster.util.WebHelper;
import io.kneo.core.dto.DocumentAccessDTO;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.IUser;
import io.kneo.core.model.user.SuperUser;
import io.kneo.core.service.AbstractService;
import io.kneo.core.service.UserService;
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
    private final RadioStationService radioStationService;
    private final LocalFileCleanupService localFileCleanupService;
    private String uploadDir;
    Validator validator;

    protected SoundFragmentService(UserService userService) {
        super(userService);
        this.localFileCleanupService = null;
        this.repository = null;
        this.radioStationService = null;
    }

    @Inject
    public SoundFragmentService(UserService userService,
                                RadioStationService radioStationService,
                                LocalFileCleanupService localFileCleanupService,
                                Validator validator,
                                SoundFragmentRepository repository,
                                BroadcasterConfig config) {
        super(userService);
        this.localFileCleanupService = localFileCleanupService;
        this.validator = validator;
        this.repository = repository;
        this.radioStationService = radioStationService;
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
        return radioStationService.getAll(10, 0, user)
                .onItem().transform(userRadioStations -> {
                    SoundFragmentDTO dto = new SoundFragmentDTO();
                    dto.setAuthor(user.getUserName());
                    dto.setLastModifier(user.getUserName());
                    dto.setNewlyUploaded(List.of());
                    dto.setUploadedFiles(List.of());
                    dto.setType(PlaylistItemType.SONG);

                    List<UUID> stationIds = userRadioStations.stream()
                            .map(RadioStation::getId)
                            .collect(Collectors.toList());
                    dto.setRepresentedInBrands(stationIds);

                    return dto;
                });
    }

    public Uni<FileMetadata> getFileBySlugName(UUID soundFragmentId, String slugName, IUser user) {
        assert repository != null;
        return repository.getFileBySlugName(soundFragmentId, slugName, user, false);
    }

    public Uni<List<BrandSoundFragmentDTO>> getBrandSoundFragments(String brandName, int limit, int offset, SoundFragmentFilterDTO filterDTO) {
        assert repository != null;
        assert radioStationService != null;

        return radioStationService.getBySlugName(brandName)
                .onItem().transformToUni(radioStation -> {
                    if (radioStation == null) {
                        return Uni.createFrom().failure(new IllegalArgumentException("Brand not found: " + brandName));
                    }
                    SoundFragmentFilter filter = toFilter(filterDTO);
                    String filterStatus = (filter != null && filter.isActivated()) ? "active" : "none";
                    BrandActivityLogger.logActivity(brandName, "brand_fragments_request",
                            "Requesting fragments (limit: %d, offset: %d, filter: %s)",
                            limit, offset, filterStatus);
                    UUID brandId = radioStation.getId();
                    //ignoring RLS deliberately
                    return repository.getForBrand(brandId, limit, offset, false, SuperUser.build(), filter)
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

    public Uni<Integer> getBrandSoundFragmentsCount(final String brand, final SoundFragmentFilterDTO filterDTO) {
        assert repository != null;

        SoundFragmentFilter filter = toFilter(filterDTO);
        String filterStatus = (filter != null && filter.isActivated()) ? "active" : "none";

        BrandActivityLogger.logActivity(brand, "count_request",
                "Requesting fragment count for brand with filter: %s", filterStatus);

        return radioStationService.getBySlugName(brand)
                .onItem().transformToUni(radioStation -> {
                    if (radioStation == null) {
                        BrandActivityLogger.logActivity(brand, "brand_not_found",
                                "Brand not found in getCountBrandSoundFragments");
                        return Uni.createFrom().failure(new IllegalArgumentException("Brand not found: " + brand));
                    }
                    UUID brandId = radioStation.getId();
                    return repository.getForBrandCount(brandId, false, SuperUser.build(), filter)
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

        List<Uni<RadioStation>> stationUnis = brandNames.stream()
                .map(radioStationService::getBySlugName)
                .collect(Collectors.toList());

        return Uni.join().all(stationUnis).andFailFast()
                .map(stations -> stations.stream()
                        .filter(Objects::nonNull)
                        .map(RadioStation::getId)
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
                    .genres(doc.getGenres())
                    .labels(doc.getLabels())
                    .album(doc.getAlbum())
                    .description(doc.getDescription())
                    .uploadedFiles(files)
                    .representedInBrands(representedInBrands)
                    .build();
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
        filter.setGenres(dto.getGenres());
        filter.setSources(dto.getSources());
        filter.setTypes(dto.getTypes());

        return filter;
    }
}