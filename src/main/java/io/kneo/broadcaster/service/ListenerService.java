package io.kneo.broadcaster.service;

import io.kneo.broadcaster.dto.BrandListenerDTO;
import io.kneo.broadcaster.dto.ListenerDTO;
import io.kneo.broadcaster.model.BrandListener;
import io.kneo.broadcaster.model.Listener;
import io.kneo.broadcaster.repository.ListenersRepository;
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

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class ListenerService extends AbstractService<Listener, ListenerDTO> {
    private static final Logger LOGGER = LoggerFactory.getLogger(ListenerService.class);
    private final ListenersRepository repository;
    private final Validator validator;
    private RadioStationService radioStationService;

    protected ListenerService() {
        super();
        this.repository = null;
        this.validator = null;
    }

    @Inject
    public ListenerService(UserRepository userRepository,
                           UserService userService,
                           RadioStationService radioStationService,
                           Validator validator,
                           ListenersRepository repository) {
        super(userRepository, userService);
        this.radioStationService = radioStationService;
        this.validator = validator;
        this.repository = repository;
    }

    public Uni<List<ListenerDTO>> getAll(final int limit, final int offset, final IUser user) {
        assert repository != null;
        return repository.getAll(limit, offset, false, user)
                .chain(list -> {
                    if (list.isEmpty()) {
                        return Uni.createFrom().item(List.of());
                    } else {
                        List<Uni<ListenerDTO>> unis = list.stream()
                                .map(this::mapToDTO)
                                .collect(Collectors.toList());
                        return Uni.join().all(unis).andFailFast();
                    }
                });
    }

    public Uni<Integer> getAllCount(final IUser user) {
        assert repository != null;
        return repository.getAllCount(user, false);
    }

    @Override
    public Uni<ListenerDTO> getDTO(UUID uuid, IUser user, LanguageCode code) {
        assert repository != null;
        user = SuperUser.build();
        return repository.findById(uuid, user.getId())
                .chain(this::mapToDTO);
    }

    public Uni<List<BrandListenerDTO>> getBrandListeners(String brandName, int limit, final int offset, IUser user) {
        assert repository != null;
        assert radioStationService != null;

        return repository.findForBrand(brandName, limit, offset, user, false)
                .chain(list -> {
                    if (list.isEmpty()) {
                        return Uni.createFrom().item(List.of());
                    } else {
                        List<Uni<BrandListenerDTO>> unis = list.stream()
                                .map(this::mapToBrandListenerDTO)
                                .collect(Collectors.toList());
                        return Uni.join().all(unis).andFailFast();
                    }

                });
    }

    public Uni<Integer> getCountBrandListeners(final String brand, final IUser user) {
        assert repository != null;
        return repository.findForBrandCount(brand, user, false);
    }

    public Uni<ListenerDTO> upsert(String id, ListenerDTO dto, IUser user) {
        assert repository != null;
        Listener entity = buildEntity(dto);

        if (id == null) {
            return repository.insert(entity, user)
                    .chain(this::mapToDTO);
        } else {
            return repository.update(UUID.fromString(id), entity, user)
                    .chain(this::mapToDTO);
        }
    }

    public Uni<Integer> archive(String id, IUser user) {
        assert repository != null;
        return repository.archive(UUID.fromString(id), user);
    }

    @Override
    public Uni<Integer> delete(String id, IUser user) {
        assert repository != null;
        return repository.delete(UUID.fromString(id), user);
    }

    private Uni<ListenerDTO> mapToDTO(Listener doc) {
        return Uni.combine().all().unis(
                userRepository.getUserName(doc.getAuthor()),
                userRepository.getUserName(doc.getLastModifier())
        ).asTuple().map(tuple -> {
            ListenerDTO dto = new ListenerDTO();
            dto.setId(doc.getId());
            dto.setAuthor(tuple.getItem1());
            dto.setRegDate(doc.getRegDate());
            dto.setLastModifier(tuple.getItem2());
            dto.setLastModifiedDate(doc.getLastModifiedDate());
            dto.setUserId(doc.getUserId());
            dto.setCountry(doc.getCountry());
            dto.setSlugName(doc.getSlugName());
            dto.setArchived(doc.getArchived());
            dto.setLocalizedName(doc.getLocalizedName());
            dto.setNickName(doc.getNickName());
            return dto;
        });
    }

    private Listener buildEntity(ListenerDTO dto) {
        Listener doc = new Listener();
        doc.setUserId(dto.getUserId());
        doc.setCountry(dto.getCountry());
        doc.setArchived(dto.getArchived());
        doc.setLocalizedName(dto.getLocalizedName());
        doc.setNickName(dto.getNickName());
        if (dto.getNickName().get(LanguageCode.en) == null) {
            doc.setSlugName(WebHelper.generateSlug(dto.getLocalizedName().get(LanguageCode.en)));
        } else {
            doc.setSlugName(WebHelper.generateSlug(dto.getNickName().get(LanguageCode.en)));
        }

        return doc;
    }

    private Uni<BrandListenerDTO> mapToBrandListenerDTO(BrandListener brandListener) {
        return mapToDTO(brandListener.getListener())
                .onItem().transform(soundFragmentDTO -> {
                    BrandListenerDTO dto = new BrandListenerDTO();
                    dto.setId(brandListener.getId());
                    dto.setListenerDTO(soundFragmentDTO);
                    return dto;
                });
    }


}
