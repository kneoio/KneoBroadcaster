package io.kneo.broadcaster.service;

import io.kneo.broadcaster.dto.BrandListenerDTO;
import io.kneo.broadcaster.dto.ListenerDTO;
import io.kneo.broadcaster.dto.ListenerFilterDTO;
import io.kneo.broadcaster.model.BrandListener;
import io.kneo.broadcaster.model.Listener;
import io.kneo.broadcaster.model.ListenerFilter;
import io.kneo.broadcaster.model.brand.Brand;
import io.kneo.broadcaster.model.cnst.ListenerType;
import io.kneo.broadcaster.repository.ListenersRepository;
import io.kneo.core.dto.DocumentAccessDTO;
import io.kneo.core.dto.document.UserDTO;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.AnonymousUser;
import io.kneo.core.model.user.IUser;
import io.kneo.core.model.user.UndefinedUser;
import io.kneo.core.repository.exception.ext.UserAlreadyExistsException;
import io.kneo.core.service.AbstractService;
import io.kneo.core.service.UserService;
import io.kneo.core.util.WebHelper;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class ListenerService extends AbstractService<Listener, ListenerDTO> {
    private static final Logger LOGGER = LoggerFactory.getLogger(ListenerService.class);
    private final ListenersRepository repository;
    private final Validator validator;
    private BrandService brandService;

    protected ListenerService() {
        super();
        this.repository = null;
        this.validator = null;
    }

    @Inject
    public ListenerService(UserService userService,
                           BrandService brandService,
                           Validator validator,
                           ListenersRepository repository) {
        super(userService);
        this.brandService = brandService;
        this.validator = validator;
        this.repository = repository;
    }

    public Uni<List<ListenerDTO>> getAll(final int limit, final int offset, final IUser user) {
        return getAll(limit, offset, user, null);
    }

    public Uni<List<ListenerDTO>> getAll(final int limit, final int offset, final IUser user, final ListenerFilterDTO filterDTO) {
        assert repository != null;
        ListenerFilter filter = toFilter(filterDTO);
        return repository.getAll(limit, offset, false, user, filter)
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

    public Uni<Integer> getAllCount(final IUser user, final ListenerFilterDTO filterDTO) {
        assert repository != null;
        ListenerFilter filter = toFilter(filterDTO);
        return repository.getAllCount(user, false, filter);
    }

    public Uni<ListenerDTO> getDTOTemplate(IUser user, LanguageCode code) {
        return brandService.getAll(10, 0, user)
                .onItem().transform(userRadioStations -> {
                    ListenerDTO dto = new ListenerDTO();
                    dto.setAuthor(user.getUserName());
                    dto.setLastModifier(user.getUserName());
                    dto.getLocalizedName().put(LanguageCode.en, "");
                    dto.getNickName().put(LanguageCode.en, Set.of());

                    List<UUID> stationIds = userRadioStations.stream()
                            .map(Brand::getId)
                            .collect(Collectors.toList());
                    dto.setListenerOf(stationIds);

                    return dto;
                });
    }

    @Override
    public Uni<ListenerDTO> getDTO(UUID uuid, IUser user, LanguageCode code) {
        assert repository != null;
        return repository.findById(uuid, user, false)
                .chain(this::mapToDTO);
    }

    public Uni<List<BrandListenerDTO>> getBrandListeners(String brandName, int limit, final int offset, IUser user, ListenerFilterDTO filterDTO) {
        assert repository != null;
        assert brandService != null;

        ListenerFilter filter = toFilter(filterDTO);
        return repository.findForBrand(brandName, limit, offset, user, false, filter)
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

    public Uni<Integer> getCountBrandListeners(final String brand, final IUser user, final ListenerFilterDTO filterDTO) {
        assert repository != null;
        ListenerFilter filter = toFilter(filterDTO);
        return repository.findForBrandCount(brand, user, false, filter);
    }

    public Uni<ListenerDTO> upsertWithStationSlug(String id, ListenerDTO dto, String stationSlug, ListenerType listenerType, IUser user) {
        assert brandService != null;
        assert repository != null;
        return brandService.getBySlugName(stationSlug)
                .chain(station -> {
                    if (station == null) {
                        return Uni.createFrom().failure(new IllegalArgumentException("Station not found: " + stationSlug));
                    }
                    
                    Listener listener = buildEntity(dto);
                    List<UUID> stationIds = new ArrayList<>();
                    stationIds.add(station.getId());
                    String slugName = WebHelper.generatePersonSlug(listener.getLocalizedName().get(LanguageCode.en));
                    listener.setSlugName(slugName);
                    
                    if (id == null) {
                        Uni<IUser> findUserUni;
                        if (dto.getUserId() > 0) {
                            findUserUni = userService.findById(dto.getUserId())
                                    .map(opt -> opt.orElse(AnonymousUser.build()));
                        } else {
                            findUserUni = userService.findByLogin(slugName);
                        }

                        return findUserUni
                                .chain(existingUser -> {
                                    if (existingUser.getId() != UndefinedUser.ID) {
                                        return repository.findByUserId(existingUser.getId())
                                                .chain(existingListener -> {
                                                    if (existingListener != null) {
                                                        return repository.update(existingListener.getId(), existingListener, stationIds, listenerType, user);
                                                    } else {
                                                        listener.setUserId(existingUser.getId());
                                                        return repository.insert(listener, stationIds, listenerType, user);
                                                    }
                                                });
                                    }
                                    
                                    UserDTO listenerUserDTO = new UserDTO();
                                    listenerUserDTO.setLogin(slugName);
                                    listenerUserDTO.setEmail(dto.getUserData().get("email"));
                                    return userService.add(listenerUserDTO, true)
                                            .chain(userId -> {
                                                listener.setUserId(userId);
                                                return repository.insert(listener, stationIds, listenerType, user);
                                            });
                                })
                                .chain(this::mapToDTO);
                    } else {
                        return repository.update(UUID.fromString(id), listener, stationIds, listenerType, user)
                                .chain(this::mapToDTO);
                    }
                });
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

        String slugName = WebHelper.generatePersonSlug(dto.getLocalizedName().get(LanguageCode.en));
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
                        listenerUserDTO.setEmail(dto.getUserData().get("email"));
                        return userService.add(listenerUserDTO, true);
                    })
                    .chain(userId -> {
                        Listener entity = buildEntity(dto);
                        entity.setUserId(userId);
                        return repository.insert(entity, dto.getListenerOf(), user);
                    })
                    .chain(this::mapToDTO);
        } else {
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
            dto.setSlugName(doc.getSlugName());
            dto.setArchived(doc.getArchived());
            dto.setLocalizedName(doc.getLocalizedName());
            dto.setNickName(doc.getNickName());
            if (doc.getUserData() != null) {
                dto.setUserData(doc.getUserData().getData());
            }
            dto.setListenerOf(tuple.getItem3());
            return dto;
        });
    }

    private Listener buildEntity(ListenerDTO dto) {
        Listener doc = new Listener();
        doc.setArchived(dto.getArchived());
        doc.setLocalizedName(dto.getLocalizedName());
        doc.setNickName(dto.getNickName());
        doc.setSlugName(dto.getSlugName());
        if (dto.getUserData() != null && !dto.getUserData().isEmpty()) {
            doc.setUserData(new io.kneo.broadcaster.model.UserData(dto.getUserData()));
        }
        return doc;
    }

    private Uni<BrandListenerDTO> mapToBrandListenerDTO(BrandListener brandListener) {
        return mapToDTO(brandListener.getListener())
                .onItem().transform(listenerDTO -> {
                    BrandListenerDTO dto = new BrandListenerDTO();
                    dto.setId(brandListener.getId());
                    dto.setListenerType(brandListener.getListenerType() != null ? brandListener.getListenerType().name() : null);
                    dto.setListenerDTO(listenerDTO);
                    return dto;
                });
    }


    private ListenerFilter toFilter(ListenerFilterDTO dto) {
        if (dto == null) {
            return null;
        }

        ListenerFilter filter = new ListenerFilter();
        filter.setActivated(dto.isActivated());
        filter.setCountries(dto.getCountries());

        return filter;
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


}