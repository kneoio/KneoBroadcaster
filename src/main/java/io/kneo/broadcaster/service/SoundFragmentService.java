package io.kneo.broadcaster.service;

import io.kneo.broadcaster.config.RadioStationPool;
import io.kneo.broadcaster.dto.SoundFragmentDTO;
import io.kneo.broadcaster.dto.SoundUploadDTO;
import io.kneo.broadcaster.model.SoundFragment;
import io.kneo.broadcaster.model.cnst.FragmentType;
import io.kneo.broadcaster.model.cnst.SourceType;
import io.kneo.broadcaster.repository.SoundFragmentRepository;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.IUser;
import io.kneo.core.repository.UserRepository;
import io.kneo.core.service.AbstractService;
import io.kneo.core.service.UserService;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.FileUpload;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Validator;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class SoundFragmentService extends AbstractService<SoundFragment, SoundFragmentDTO> {
    private final SoundFragmentRepository repository;
    private final RadioService radioService;
    private final RadioStationPool radiostationPool;
    Validator validator;

    protected SoundFragmentService() {
        super(null, null);
        this.repository = null;
        this.radioService = null;
        this.radiostationPool = null;
    }

    @Inject
    public SoundFragmentService(UserRepository userRepository,
                                UserService userService,
                                RadioService service,
                                RadioStationPool radiostationPool,
                                Validator validator,
                                SoundFragmentRepository repository) {
        super(userRepository, userService);
        this.validator = validator;
        this.repository = repository;
        this.radioService = service;
        this.radiostationPool = radiostationPool;
    }

    public Uni<List<SoundFragmentDTO>> getAll(final int limit, final int offset, final IUser user) {
        assert repository != null;
        return repository.getAll(limit, offset, user)
                .chain(list -> {
                    List<Uni<SoundFragmentDTO>> unis = list.stream()
                            .map(this::mapToDTO)
                            .collect(Collectors.toList());
                    return Uni.join().all(unis).andFailFast();
                });
    }

    public Uni<Integer> getAllCount(final IUser user) {
        assert repository != null;
        return repository.getAllCount(user);
    }

    @Override
    public Uni<SoundFragmentDTO> getDTO(UUID uuid, IUser user, LanguageCode code) {
        assert repository != null;
        return repository.findById(uuid, user.getId())
                .chain(this::mapToDTO);
    }

    public Uni<SoundFragment> getById(UUID uuid, IUser user) {
        assert repository != null;
        return repository.findById(uuid, user.getId());
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
                    .fileUri(doc.getFileUri())
                    .localPath(doc.getLocalPath())
                    .type(doc.getType())
                    .name(doc.getTitle())
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
        doc.setFileUri(dto.getFileUri());
        doc.setLocalPath(dto.getLocalPath());
        doc.setType(dto.getType());
        doc.setTitle(dto.getName());
        doc.setArtist(dto.getArtist());
        doc.setGenre(dto.getGenre());
        doc.setAlbum(dto.getAlbum());
        return doc;
    }

    public Uni<Integer> delete(String id, IUser user) {
        assert repository != null;
        return repository.delete(UUID.fromString(id), user);
    }

    public Uni<Void> streamDirectly(String brand, FileUpload upload) {
        assert radioService != null;
        return radioService.addFileUploadToPlaylist(brand, upload);
    }

    public Uni<SoundFragmentDTO> processUploadWithIntro(String brand, FileUpload file, SoundUploadDTO uploadDTO, IUser user) {
        SoundFragment entity = new SoundFragment();
        entity.setLocalPath(file.uploadedFileName());
        entity.setTitle(file.fileName());
        entity.setSource(SourceType.LOCAL_DISC);
        entity.setType(FragmentType.SONG);

        if (uploadDTO.isAutoGenerateIntro() && (uploadDTO.getIntroductionText() == null || uploadDTO.getIntroductionText().isEmpty())) {
            uploadDTO.setIntroductionText("Now playing: " + file.fileName());
        }

        return repository.insert(entity, List.of(file), user)
                .chain(doc -> streamDirectly(brand, file)
                        .onItem().transform(v -> doc))
                .chain(this::mapToDTO);
    }
}