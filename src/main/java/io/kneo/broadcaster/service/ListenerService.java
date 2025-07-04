package io.kneo.broadcaster.service;

import io.kneo.broadcaster.dto.BrandListenerDTO;
import io.kneo.broadcaster.dto.ListenerDTO;
import io.kneo.broadcaster.model.BrandListener;
import io.kneo.broadcaster.model.Listener;
import io.kneo.broadcaster.repository.ListenersRepository;
import io.kneo.broadcaster.util.WebHelper;
import io.kneo.core.dto.document.UserDTO;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.IUser;
import io.kneo.core.model.user.UndefinedUser;
import io.kneo.core.repository.exception.ext.UserAlreadyExistsException;
import io.kneo.core.service.AbstractService;
import io.kneo.core.service.UserService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
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
    public ListenerService(UserService userService,
                           RadioStationService radioStationService,
                           Validator validator,
                           ListenersRepository repository) {
        super(userService);
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
        return repository.findById(uuid, user, false)
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
        assert validator != null;
        Set<ConstraintViolation<ListenerDTO>> violations = validator.validate(dto);
        if (!violations.isEmpty()) {
            String errorMessage = violations.stream()
                    .map(ConstraintViolation::getMessage)
                    .collect(Collectors.joining(", "));
            return Uni.createFrom().failure(new IllegalArgumentException("Validation failed: " + errorMessage));
        }

        String slugName;
        if (dto.getNickName().get(LanguageCode.en) != null && !dto.getNickName().get(LanguageCode.en).isEmpty()) {
            slugName = WebHelper.generateSlug(dto.getNickName().get(LanguageCode.en));
        } else {
            slugName = WebHelper.generateSlug(dto.getLocalizedName().get(LanguageCode.en));
        }
        dto.setSlugName(slugName);

        if (id == null) {
            return userService.findByLogin(slugName)
                    .chain(existingUser -> {
                        if (existingUser.getId() != UndefinedUser.ID) {
                            return Uni.createFrom().failure(
                                    new UserAlreadyExistsException(slugName));
                        }

                        UserDTO listenerUserDTO = new UserDTO();
                        listenerUserDTO.setLogin(slugName);
                        return userService.add(listenerUserDTO, true);
                    })
                    .chain(userId -> {
                        Listener entity = buildEntity(dto);
                        entity.setUserId(userId);
                        return repository.insert(entity, dto.getListenerOf(), user);
                    })
                    .chain(this::mapToDTO)
                    .onFailure().invoke(throwable -> {
                        LOGGER.error("Failed to create listener with slugName: {}", slugName, throwable);
                    });
        } else {
            //TODO if slugName change it is not handle
            Listener entity = buildEntity(dto);
            return repository.update(UUID.fromString(id), entity, dto.getListenerOf(), user)
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
        assert repository != null;
        return Uni.combine().all().unis(
                userService.getUserName(doc.getAuthor()),
                userService.getUserName(doc.getLastModifier()),
                repository.getBrandsForListener(doc.getId(), doc.getAuthor())
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
            dto.setListenerOf(tuple.getItem3());
            return dto;
        });
    }

    private Listener buildEntity(ListenerDTO dto) {
        Listener doc = new Listener();
        doc.setCountry(dto.getCountry());
        doc.setArchived(dto.getArchived());
        doc.setLocalizedName(dto.getLocalizedName());
        doc.setNickName(dto.getNickName());
        doc.setSlugName(dto.getSlugName());
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
