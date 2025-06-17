package io.kneo.broadcaster.service;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.dto.BrandSoundFragmentDTO;
import io.kneo.broadcaster.dto.SoundFragmentDTO;
import io.kneo.broadcaster.dto.UploadFileDTO;
import io.kneo.broadcaster.model.BrandSoundFragment;
import io.kneo.broadcaster.model.FileMetadata;
import io.kneo.broadcaster.model.SoundFragment;
import io.kneo.broadcaster.repository.SoundFragmentRepository;
import io.kneo.broadcaster.util.WebHelper;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class SoundFragmentService extends AbstractService<SoundFragment, SoundFragmentDTO> {
    private static final Logger LOGGER = LoggerFactory.getLogger(SoundFragmentService.class);

    private final SoundFragmentRepository repository;
    private final RadioStationService radioStationService;
    private final BroadcasterConfig config;
    private String uploadDir;
    Validator validator;

    protected SoundFragmentService(BroadcasterConfig config) {
        super(null);
        this.config = config;
        this.repository = null;
        this.radioStationService = null;
    }

    @Inject
    public SoundFragmentService(UserRepository userRepository,
                                UserService userService,
                                RadioStationService radioStationService,
                                Validator validator,
                                SoundFragmentRepository repository, BroadcasterConfig config) {
        super(userRepository, userService);
        this.validator = validator;
        this.repository = repository;
        this.radioStationService = radioStationService;
        uploadDir = config.getPathUploads() + "/sound-fragments-controller";
        this.config = config;
    }

    public Uni<List<SoundFragmentDTO>> getAll(final int limit, final int offset, final IUser user) {
        assert repository != null;
        return repository.getAll(limit, offset, false, user)
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
        assert repository != null;
        return repository.getAllCount(user, false);
    }

    public Uni<List<SoundFragment>> getAll(final int limit, final int offset) {
        assert repository != null;
        return repository.getAll(limit, offset, false, SuperUser.build());
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



    public Uni<BrandSoundFragmentDTO> getBrandSoundFragmentDTO(UUID uuid, IUser user, LanguageCode code, boolean populateAllBrands) {
        assert repository != null;
        return repository.findBrandSoundFragmentById(uuid, user)
                .chain(doc -> {
                    if (populateAllBrands) {
                        return repository.populateAllBrands(doc, user)
                                .chain(this::mapToBrandSoundFragmentDTO);
                    } else {
                        return mapToBrandSoundFragmentDTO(doc);
                    }
                });
    }

    public Uni<FileMetadata> getFile(UUID soundFragmentId, String slugName, IUser user) {
        assert repository != null;
        return repository.getFileById(soundFragmentId, slugName, user, false);
    }

    public Uni<FileMetadata> getFile(UUID soundFragmentId) {
        assert repository != null;
        return repository.getFileById(soundFragmentId);
    }

    public Uni<List<BrandSoundFragment>> getForBrand(String brandName, int quantity, boolean shuffle, IUser user) {
        assert repository != null;
        assert radioStationService != null;
        LOGGER.debug("Get fragments for brand: {}, quantity: {}, shuffle: {}", brandName, quantity, shuffle);

        return radioStationService.findByBrandName(brandName)
                .onItem().transformToUni(radioStation -> {
                    if (radioStation == null) {
                        return Uni.createFrom().failure(new IllegalArgumentException("Brand not found: " + brandName));
                    }
                    UUID brandId = radioStation.getId();
                    int limit = quantity;
                    if (shuffle) {
                        limit = 0;
                    }
                    return repository.findForBrand(brandId, limit, 0, false, user)
                            .chain(fragments -> {
                                if (shuffle && fragments != null && !fragments.isEmpty()) {
                                    Collections.shuffle(fragments);
                                    if (quantity > 0 && fragments.size() > quantity) {
                                        fragments = fragments.subList(0, quantity);
                                    }
                                }
                                return Uni.createFrom().item(fragments);
                            });
                })
                .onFailure().recoverWithUni(failure -> {
                    LOGGER.error("Failed to get fragments for brand: {}", brandName, failure);
                    return Uni.createFrom().failure(failure);
                });
    }

    public Uni<List<BrandSoundFragmentDTO>> getBrandSoundFragments(String brandName, int limit, int offset, boolean populateAllBrands, IUser user) {
        assert repository != null;
        assert radioStationService != null;

        return radioStationService.findByBrandName(brandName)
                .onItem().transformToUni(radioStation -> {
                    if (radioStation == null) {
                        return Uni.createFrom().failure(new IllegalArgumentException("Brand not found: " + brandName));
                    }
                    UUID brandId = radioStation.getId();

                    return repository.findForBrand(brandId, limit, offset, false, user)
                            .chain(fragments -> {
                                List<Uni<BrandSoundFragmentDTO>> unis;
                                if (populateAllBrands) {
                                    unis = fragments.stream()
                                            .map(fragment -> repository.populateAllBrands(fragment, user)
                                                    .chain(this::mapToBrandSoundFragmentDTO))
                                            .collect(Collectors.toList());
                                } else {
                                    unis = fragments.stream()
                                            .map(this::mapToBrandSoundFragmentDTO)
                                            .collect(Collectors.toList());
                                }
                                return Uni.join().all(unis).andFailFast();
                            });
                })
                .onFailure().recoverWithUni(failure -> {
                    LOGGER.error("Failed to get fragments for brand: {}", brandName, failure);
                    return Uni.createFrom().failure(failure);
                });
    }

    public Uni<Integer> getCountBrandSoundFragments(final String brand, final IUser user) {
        assert repository != null;
        return radioStationService.findByBrandName(brand)
                .onItem().transformToUni(radioStation -> {
                    if (radioStation == null) {
                        return Uni.createFrom().failure(new IllegalArgumentException("Brand not found: " + brand));
                    }
                    UUID brandId = radioStation.getId();
                    return repository.findForBrandCount(brandId, false, user);
                })
                .onFailure().recoverWithUni(failure -> {
                    LOGGER.error("Failed to get fragments count for brand: {}", brand, failure);
                    return Uni.createFrom().failure(failure);
                });
    }

    public Uni<SoundFragmentDTO> upsert(String id, SoundFragmentDTO dto, IUser user, LanguageCode code) {
        FileMetadata fileMetadata = new FileMetadata();
        if (dto.getNewlyUploaded() != null && !dto.getNewlyUploaded().isEmpty()) {
            dto.getNewlyUploaded()
                    .forEach(fileName -> {
                        if (id == null) {
                            fileMetadata.setFilePath(Path.of(uploadDir + "/" + user.getUserName() + "/null/" + fileName));
                        } else {
                            fileMetadata.setFilePath(Path.of(uploadDir + "/" + user.getUserName() + "/" + id + "/" + fileName));
                        }
                    });
        }
        SoundFragment entity = buildEntity(dto);
        entity.setFileMetadataList(List.of(fileMetadata));
        if (id == null) {
            return repository.insert(entity, user)
                    .chain(doc -> mapToDTO(doc, true, null));
        } else {
            return repository.update(UUID.fromString(id), entity, user)
                    .chain(doc -> mapToDTO(doc, true, null));
        }
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
                            files.add(UploadFileDTO.builder()
                                    .id(meta.getSlugName())
                                    .name(meta.getFileOriginalName())
                                    .status("finished")
                                    .url("/api/soundfragments/files/" + doc.getId() + "/" + meta.getSlugName())
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
}