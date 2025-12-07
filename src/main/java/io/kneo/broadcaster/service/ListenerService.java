package io.kneo.broadcaster.service;

import io.kneo.broadcaster.dto.BrandListenerDTO;
import io.kneo.broadcaster.dto.ListenerDTO;
import io.kneo.broadcaster.dto.ListenerFilterDTO;
import io.kneo.broadcaster.dto.aihelper.llmtool.ListenerAiDTO;
import io.kneo.broadcaster.model.BrandListener;
import io.kneo.broadcaster.model.Listener;
import io.kneo.broadcaster.model.ListenerFilter;
import io.kneo.broadcaster.model.cnst.ListenerType;
import io.kneo.broadcaster.model.radiostation.RadioStation;
import io.kneo.broadcaster.repository.ListenersRepository;
import io.kneo.core.dto.DocumentAccessDTO;
import io.kneo.core.dto.document.UserDTO;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.AnonymousUser;
import io.kneo.core.model.user.IUser;
import io.kneo.core.model.user.SuperUser;
import io.kneo.core.model.user.UndefinedUser;
import io.kneo.core.repository.exception.ext.UserAlreadyExistsException;
import io.kneo.core.service.AbstractService;
import io.kneo.core.service.UserService;
import io.kneo.core.util.WebHelper;
import io.kneo.officeframe.cnst.CountryCode;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class ListenerService extends AbstractService<Listener, ListenerDTO> {
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

    public Uni<Integer> getAllCount(final IUser user) {
        return getAllCount(user, null);
    }

    public Uni<Integer> getAllCount(final IUser user, final ListenerFilterDTO filterDTO) {
        assert repository != null;
        ListenerFilter filter = toFilter(filterDTO);
        return repository.getAllCount(user, false, filter);
    }

    public Uni<ListenerDTO> getDTOTemplate(IUser user, LanguageCode code) {
        return radioStationService.getAll(10, 0, user)
                .onItem().transform(userRadioStations -> {
                    ListenerDTO dto = new ListenerDTO();
                    dto.setAuthor(user.getUserName());
                    dto.setLastModifier(user.getUserName());
                    dto.getLocalizedName().put(LanguageCode.en, "");
                    dto.getNickName().put(LanguageCode.en, "");

                    List<UUID> stationIds = userRadioStations.stream()
                            .map(RadioStation::getId)
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
        assert radioStationService != null;

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
        assert radioStationService != null;
        assert repository != null;
        return radioStationService.getBySlugName(stationSlug)
                .chain(station -> {
                    if (station == null) {
                        return Uni.createFrom().failure(new IllegalArgumentException("Station not found: " + stationSlug));
                    }
                    
                    Listener listener = buildEntity(dto);
                    listener.setCountry(station.getCountry());
                    
                    List<UUID> stationIds = new ArrayList<>();
                    stationIds.add(station.getId());
                    
                    String slugName;
                    if (listener.getNickName().get(LanguageCode.en) != null && !listener.getNickName().get(LanguageCode.en).isEmpty()) {
                        slugName = WebHelper.generateSlug(listener.getNickName().get(LanguageCode.en));
                    } else {
                        slugName = WebHelper.generateSlug(listener.getLocalizedName().get(LanguageCode.en));
                    }
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
                                                        if (listenerType == ListenerType.OWNER) {
                                                            existingListener.setListenerType(ListenerType.OWNER);
                                                        }
                                                        return repository.update(existingListener.getId(), existingListener, stationIds, user);
                                                    } else {
                                                        listener.setUserId(existingUser.getId());
                                                        listener.setListenerType(listenerType);
                                                        return repository.insert(listener, stationIds, user);
                                                    }
                                                });
                                    }
                                    
                                    UserDTO listenerUserDTO = new UserDTO();
                                    listenerUserDTO.setLogin(slugName);
                                    return userService.add(listenerUserDTO, true)
                                            .chain(userId -> {
                                                listener.setUserId(userId);
                                                listener.setListenerType(listenerType);
                                                return repository.insert(listener, stationIds, user);
                                            });
                                })
                                .chain(this::mapToDTO);
                    } else {
                        return repository.update(UUID.fromString(id), listener, stationIds, user)
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
                        entity.setListenerType(ListenerType.REGULAR);
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
            dto.setTelegramName(doc.getTelegramName());
            dto.setCountry(doc.getCountry().name());
            dto.setSlugName(doc.getSlugName());
            dto.setArchived(doc.getArchived());
            dto.setLocalizedName(doc.getLocalizedName());
            dto.setNickName(doc.getNickName());
            dto.setListenerOf(tuple.getItem3());
            dto.setListenerType(doc.getListenerType() != null ? doc.getListenerType().name() : null);
            return dto;
        });
    }

    private Listener buildEntity(ListenerDTO dto) {
        Listener doc = new Listener();
        doc.setCountry(CountryCode.fromString(dto.getCountry()));
        doc.setArchived(dto.getArchived());
        doc.setLocalizedName(dto.getLocalizedName());
        doc.setNickName(dto.getNickName());
        doc.setSlugName(dto.getSlugName());
        doc.setTelegramName(dto.getTelegramName());
        if (dto.getListenerType() != null) {
            doc.setListenerType(ListenerType.valueOf(dto.getListenerType()));
        } else {
            doc.setListenerType(ListenerType.REGULAR);
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

    public Uni<ListenerAiDTO> getAiBrandListenerByTelegramName(String telegramName) {
        assert repository != null;
        assert radioStationService != null;
        return repository.findByTelegramName(telegramName)
                .onItem().transformToUni(listener -> {
                    if (listener == null) {
                        return Uni.createFrom().nullItem();
                    }
                    return repository.getBrandsForListener(listener.getId(), listener.getAuthor())
                            .chain(brandIds -> {
                                if (brandIds == null || brandIds.isEmpty()) {
                                    ListenerAiDTO aiDto = new ListenerAiDTO();
                                    aiDto.setTelegramName(listener.getTelegramName());
                                    aiDto.setCountry(listener.getCountry().name());
                                    aiDto.setLocalizedName(listener.getLocalizedName());
                                    aiDto.setNickName(listener.getNickName());
                                    aiDto.setSlugName(listener.getSlugName());
                                    aiDto.setListenerOf(List.of());
                                    return Uni.createFrom().item(aiDto);
                                }

                                List<Uni<List<String>>> brandUnis = brandIds.stream()
                                        .map(brandId -> radioStationService.getDTO(brandId, SuperUser.build(), LanguageCode.en)
                                                .onItem().transform(rsDto -> {
                                                    if (rsDto == null) return null;
                                                    List<String> entry = new ArrayList<>();
                                                    entry.add(rsDto.getSlugName());
                                                    if (rsDto.getLocalizedName() != null) {
                                                        entry.addAll(rsDto.getLocalizedName().values());
                                                    }
                                                    return entry;
                                                }))
                                        .collect(Collectors.toList());

                                return Uni.join().all(brandUnis).andFailFast()
                                        .onItem().transform(list -> list.stream().filter(java.util.Objects::nonNull).collect(Collectors.toList()))
                                        .onItem().transform(stationEntries -> {
                                            ListenerAiDTO aiDto = new ListenerAiDTO();
                                            aiDto.setTelegramName(listener.getTelegramName());
                                            aiDto.setCountry(listener.getCountry().name());
                                            aiDto.setLocalizedName(listener.getLocalizedName());
                                            aiDto.setNickName(listener.getNickName());
                                            aiDto.setSlugName(listener.getSlugName());
                                            aiDto.setListenerOf(stationEntries);
                                            return aiDto;
                                        });
                            });
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