package io.kneo.broadcaster.service;

import io.kneo.broadcaster.dto.BrandSoundFragmentDTO;
import io.kneo.broadcaster.dto.SoundFragmentDTO;
import io.kneo.broadcaster.model.BrandSoundFragment;
import io.kneo.broadcaster.model.FileData;
import io.kneo.broadcaster.model.SoundFragment;
import io.kneo.broadcaster.model.cnst.FragmentActionType;
import io.kneo.broadcaster.repository.SoundFragmentRepository;
import io.kneo.broadcaster.service.stream.RadioStationPool;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.IUser;
import io.kneo.core.model.user.SuperUser;
import io.kneo.core.service.AbstractService;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.FileUpload;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class SoundFragmentService extends AbstractService<SoundFragment, SoundFragmentDTO> {
    private static final Logger LOGGER = LoggerFactory.getLogger(SoundFragmentService.class);

    private final SoundFragmentRepository repository;
    private final RadioService radioService;
    private final RadioStationService radioStationService;
    private final RadioStationPool radiostationPool;
    Validator validator;

    protected SoundFragmentService() {
        super(null, null);
        this.repository = null;
        this.radioStationService = null;
        this.radioService = null;
        this.radiostationPool = null;
    }

    @Inject
    public SoundFragmentService(RadioService service,
                                RadioStationService radioStationService,
                                RadioStationPool radiostationPool,
                                Validator validator,
                                SoundFragmentRepository repository) {
        super();
        this.validator = validator;
        this.repository = repository;
        this.radioService = service;
        this.radioStationService = radioStationService;
        this.radiostationPool = radiostationPool;
    }

    public Uni<List<SoundFragmentDTO>> getAll(final int limit, final int offset, final IUser user) {
        assert repository != null;
        return repository.getAll(limit, offset, user)
                .chain(list -> {
                    if (list.isEmpty()) {
                        return Uni.createFrom().item(List.of());
                    } else {
                        List<Uni<SoundFragmentDTO>> unis = list.stream()
                                .map(this::mapToDTO)
                                .collect(Collectors.toList());
                        return Uni.join().all(unis).andFailFast();
                    }
                });
    }


    public Uni<Integer> getAllCount(final IUser user) {
        assert repository != null;
        return repository.getAllCount(user);
    }

    public Uni<List<SoundFragment>> getAll(final int limit, final int offset) {
        assert repository != null;
        return repository.getAll(limit, offset, SuperUser.build());
    }

    @Override
    public Uni<SoundFragmentDTO> getDTO(UUID uuid, IUser user, LanguageCode code) {
        assert repository != null;
        return repository.findById(uuid, user.getId())
                .chain(this::mapToDTO);
    }

    public Uni<FileData> getFile(UUID fileId, IUser user) {
        assert repository != null;
        return repository.getFileById(fileId, user.getId());
    }

    public Uni<List<BrandSoundFragment>> getForBrand(String brandName, int quantity, boolean shuffle) {
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
                    return repository.findForBrand(brandId, limit, 0)
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

    public Uni<Void> updateForBrand(UUID soundFragmentId, String brandName, FragmentActionType actionType) {
        assert repository != null;
        assert radioStationService != null;
        LOGGER.debug("Action: {} for brand: {}, sound fragment: {}", actionType.name(), brandName, soundFragmentId);
        return radioStationService.findByBrandName(brandName)
                .onItem().transformToUni(radioStation -> {
                    if (radioStation == null) {
                        return Uni.createFrom().failure(new IllegalArgumentException("Brand not found: " + brandName));
                    }

                    UUID brandId = radioStation.getId();
                    return repository.updatePlayedByBrand(brandId, soundFragmentId)
                            .onItem().transformToUni(updateCount -> {
                                if (updateCount == 0) {
                                    return Uni.createFrom().failure(new IllegalArgumentException("No matching record found for brand: " + brandName + " and fragment: " + soundFragmentId));
                                }
                                return Uni.createFrom().voidItem();
                            });
                })
                .onFailure().recoverWithUni(failure -> {
                    LOGGER.error("Failed to update fragment for brand: {}", brandName, failure);
                    return Uni.createFrom().failure(failure);
                });
    }

    public Uni<List<BrandSoundFragmentDTO>> getBrandSoundFragments(String brandName, int limit) {
        assert repository != null;
        assert radioStationService != null;
        return radioStationService.findByBrandName(brandName)
                .onItem().transformToUni(radioStation -> {
                    if (radioStation == null) {
                        return Uni.createFrom().failure(new IllegalArgumentException("Brand not found: " + brandName));
                    }
                    UUID brandId = radioStation.getId();
                    return repository.findForBrand(brandId, limit, 0)
                            .chain(fragments -> {
                                List<Uni<BrandSoundFragmentDTO>> unis = fragments.stream()
                                        .map(this::mapToBrandSoundFragmentDTO)
                                        .collect(Collectors.toList());
                                return Uni.join().all(unis).andFailFast();
                            });
                })
                .onFailure().recoverWithUni(failure -> {
                    LOGGER.error("Failed to get fragments for brand: {}", brandName, failure);
                    return Uni.createFrom().failure(failure);
                });
    }

    public Uni<Integer> getBrandSoundFragmentCount(String brandName) {
        assert repository != null;
        assert radioStationService != null;
        return radioStationService.findByBrandName(brandName)
                .onItem().transformToUni(radioStation -> {
                    if (radioStation == null) {
                        return Uni.createFrom().failure(new IllegalArgumentException("Brand not found: " + brandName));
                    }
                    UUID brandId = radioStation.getId();
                    return repository.getCountForBrand(brandId)
                            .chain(count -> {
                                return Uni.createFrom().item(count);
                            });
                })
                .onFailure().recoverWithUni(failure -> {
                    LOGGER.error("Failed to get fragments for brand: {}", brandName, failure);
                    return Uni.createFrom().failure(failure);
                });
    }

    public Uni<SoundFragmentDTO> upsert(String id, SoundFragmentDTO dto, List<FileUpload> files, IUser user, LanguageCode code) {
        assert repository != null;
        SoundFragment entity = buildEntity(dto);

        if (id == null) {
            return repository.insert(entity, files, user)
                    .chain(this::mapToDTO);
        } else {
            return repository.update(UUID.fromString(id), entity, files, user)
                    .chain(this::mapToDTO);
        }
    }

    private Uni<SoundFragmentDTO> mapToDTO(SoundFragment doc) {
        return Uni.combine().all().unis(
                userRepository.getUserName(doc.getAuthor()),
                userRepository.getUserName(doc.getLastModifier())
        ).asTuple().onItem().transform(tuple -> {
            String author = tuple.getItem1();
            String lastModifier = tuple.getItem2();

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
        return doc;
    }

    public Uni<Integer> delete(String id, IUser user) {
        assert repository != null;
        return repository.delete(UUID.fromString(id), user);
    }


    private Uni<BrandSoundFragmentDTO> mapToBrandSoundFragmentDTO(BrandSoundFragment fragment) {
        return mapToDTO(fragment.getSoundFragment())
                .onItem().transform(soundFragmentDTO -> {
                    BrandSoundFragmentDTO dto = new BrandSoundFragmentDTO();
                    dto.setId(fragment.getId());
                    dto.setSoundFragmentDTO(soundFragmentDTO);
                    dto.setPlayedByBrandCount(fragment.getPlayedByBrandCount());
                    dto.setLastTimePlayedByBrand(fragment.getPlayedTime());
                    return dto;
                });
    }
}