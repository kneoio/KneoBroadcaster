package io.kneo.broadcaster.service;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.dto.ListenerDTO;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.dto.radiostation.AiOverridingDTO;
import io.kneo.broadcaster.dto.radiostation.BrandDTO;
import io.kneo.broadcaster.dto.radiostation.BrandScriptEntryDTO;
import io.kneo.broadcaster.dto.radiostation.ProfileOverridingDTO;
import io.kneo.broadcaster.model.brand.AiOverriding;
import io.kneo.broadcaster.model.brand.Brand;
import io.kneo.broadcaster.model.brand.BrandScriptEntry;
import io.kneo.broadcaster.model.brand.ProfileOverriding;
import io.kneo.broadcaster.model.cnst.ListenerType;
import io.kneo.broadcaster.repository.BrandRepository;
import io.kneo.broadcaster.service.stream.RadioStationPool;
import io.kneo.core.dto.DocumentAccessDTO;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.IUser;
import io.kneo.core.model.user.SuperUser;
import io.kneo.core.service.AbstractService;
import io.kneo.core.service.UserService;
import io.kneo.core.util.WebHelper;
import io.kneo.officeframe.cnst.CountryCode;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class BrandService extends AbstractService<Brand, BrandDTO> {
    private static final Logger LOGGER = LoggerFactory.getLogger(BrandService.class);

    private final BrandRepository repository;

    private final BroadcasterConfig broadcasterConfig;

    private final RadioStationPool radiostationPool;

    @Inject
    Provider<ListenerService> listenerService;

    ScriptService scriptService;

    @Inject
    public BrandService(
            UserService userService,
            ScriptService scriptService,
            BrandRepository repository,
            RadioStationPool radiostationPool,
            BroadcasterConfig broadcasterConfig
    ) {
        super(userService);
        this.scriptService =scriptService;
        this.repository = repository;
        this.radiostationPool = radiostationPool;
        this.broadcasterConfig = broadcasterConfig;
    }

    public Uni<List<BrandDTO>> getAllDTO(final int limit, final int offset, final IUser user) {
        assert repository != null;
        return repository.getAll(limit, offset, false, user)
                .chain(list -> {
                    if (list.isEmpty()) {
                        return Uni.createFrom().item(List.of());
                    } else {
                        List<Uni<BrandDTO>> unis = list.stream()
                                .map(this::mapToDTO)
                                .collect(Collectors.toList());
                        return Uni.join().all(unis).andFailFast();
                    }
                });
    }

    public Uni<List<BrandDTO>> getAllDTOFiltered(final int limit, final int offset, final IUser user, final String country, final String query) {
        assert repository != null;
        return repository.getAllFiltered(limit, offset, false, user, country, query)
                .chain(list -> {
                    if (list.isEmpty()) {
                        return Uni.createFrom().item(List.of());
                    } else {
                        List<Uni<BrandDTO>> unis = list.stream()
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

    public Uni<List<Brand>> getAll(final int limit, final int offset) {
        return repository.getAll(limit, offset, false, SuperUser.build());
    }

    public Uni<List<Brand>> getAll(final int limit, final int offset, IUser user) {
        return repository.getAll(limit, offset, false, user);
    }

    public Uni<Brand> getById(UUID id, IUser user) {
        return repository.findById(id, user, true);
    }

    public Uni<Brand> getBySlugName(String name) {
        return repository.getBySlugName(name)
                .chain(brand -> {
                    if (brand == null) {
                        return Uni.createFrom().nullItem();
                    }
                    return scriptService.getAllScriptsForBrandWithScenes(brand.getId(), SuperUser.build())
                            .map(brandScripts -> {
                                List<BrandScriptEntry> entries = brandScripts.stream()
                                        .map(bs -> new BrandScriptEntry(
                                                bs.getScript().getId(),
                                                bs.getUserVariables()
                                        ))
                                        .collect(Collectors.toList());
                                brand.setScripts(entries);
                                return brand;
                            });
                });
    }


    public Uni<Brand> getBySlugName(String name, IUser user) {
        return repository.getBySlugName(name, user, false);
    }

    @Override
    public Uni<Integer> delete(String id, IUser user) {
        assert repository != null;
        return repository.delete(UUID.fromString(id), user);
    }

    @Override
    public Uni<BrandDTO> getDTO(UUID id, IUser user, LanguageCode language) {
        assert repository != null;
        return repository.findById(id, user, false).chain(this::mapToDTO);
    }

    public Uni<OffsetDateTime> findLastAccessTimeByStationName(String stationName) {
        return repository.findLastAccessTimeByStationName(stationName);
    }

    public Uni<BrandDTO> upsert(String id, BrandDTO dto, IUser user, LanguageCode code) {
        assert repository != null;
        LOGGER.info("Upserting radio station with DTO scripts: {}", dto.getScripts());
        Brand entity = buildEntity(dto);
        LOGGER.info("Built entity with scripts: {}", entity.getScripts());

        Uni<Brand> saveOperation;
        if (id == null) {
            entity.setPopularityRate(5);
            saveOperation = repository.insert(entity, user);
        } else {
            saveOperation = repository.update(UUID.fromString(id), entity, user);
        }

        return saveOperation.chain(savedEntity -> {
            ListenerDTO listenerDTO = new ListenerDTO();
            listenerDTO.setCountry(savedEntity.getCountry().name());
            listenerDTO.setUserId(user.getId());
            EnumMap<LanguageCode, String> names = new EnumMap<>(LanguageCode.class);
            names.put(LanguageCode.en, user.getUserName());
            listenerDTO.setLocalizedName(names);
            listenerDTO.setNickName(names);

            return listenerService.get().upsertWithStationSlug(null, listenerDTO, savedEntity.getSlugName(), ListenerType.OWNER, user)
                    .onFailure().invoke(t -> LOGGER.error("Failed to ensure owner listener for station: {}", savedEntity.getSlugName(), t))
                    .onItem().ignore().andContinueWithNull()
                    .chain(() -> {
                        return Uni.createFrom().item(savedEntity);
                    });
        }).chain(this::mapToDTO);
    }

    public Uni<Integer> archive(String id, IUser user) {
        assert repository != null;
        return repository.findById(UUID.fromString(id), user, false)
                .chain(radioStation -> {
                    if (radioStation != null && radioStation.getSlugName() != null) {
                        return radiostationPool.stopAndRemove(radioStation.getSlugName())
                                .onFailure().invoke(failure ->
                                        LOGGER.warn("Failed to stop radio station {} during archive: {}",
                                                radioStation.getSlugName(), failure.getMessage()))
                                .onItem().ignore().andSwitchTo(
                                        repository.archive(UUID.fromString(id), user)
                                );
                    } else {
                        return repository.archive(UUID.fromString(id), user);
                    }
                });
    }

    public Uni<Integer> archive(UUID id) {
        assert repository != null;
        return repository.archive(id, SuperUser.build());
    }

    private Uni<BrandDTO> mapToDTO(Brand doc) {
        return Uni.combine().all().unis(
                userService.getUserName(doc.getAuthor()),
                userService.getUserName(doc.getLastModifier()),
                radiostationPool.getLiveStatus(doc.getSlugName()),
                repository.getScriptEntriesForBrand(doc.getId())
        ).asTuple().map(tuple -> {
            BrandDTO dto = new BrandDTO();
            dto.setId(doc.getId());
            dto.setAuthor(tuple.getItem1());
            dto.setRegDate(doc.getRegDate());
            dto.setLastModifier(tuple.getItem2());
            dto.setLastModifiedDate(doc.getLastModifiedDate());
            dto.setLocalizedName(doc.getLocalizedName());
            dto.setCountry(doc.getCountry() != null ? doc.getCountry().name() : null);
            dto.setColor(doc.getColor());
            dto.setTimeZone(doc.getTimeZone().getId());
            dto.setDescription(doc.getDescription());
            dto.setTitleFont(doc.getTitleFont());
            dto.setSlugName(doc.getSlugName());
            dto.setManagedBy(doc.getManagedBy());
            dto.setBitRate(doc.getBitRate());
            dto.setAiAgentId(doc.getAiAgentId());
            dto.setProfileId(doc.getProfileId());
            dto.setOneTimeStreamPolicy(doc.getOneTimeStreamPolicy());
            dto.setSubmissionPolicy(doc.getSubmissionPolicy());
            dto.setMessagingPolicy(doc.getMessagingPolicy());
            dto.setIsTemporary(doc.getIsTemporary());
            dto.setPopularityRate(doc.getPopularityRate());

            if (doc.getAiOverriding() != null) {
                AiOverridingDTO aiDto = new AiOverridingDTO();
                aiDto.setName(doc.getAiOverriding().getName());
                aiDto.setPrompt(doc.getAiOverriding().getPrompt());
                aiDto.setPrimaryVoice(doc.getAiOverriding().getPrimaryVoice());
                dto.setAiOverriding(aiDto);
                dto.setAiOverridingEnabled(true);
            } else {
                dto.setAiOverridingEnabled(false);
            }

            if (doc.getProfileOverriding() != null) {
                ProfileOverridingDTO profileDto = new ProfileOverridingDTO();
                profileDto.setName(doc.getProfileOverriding().getName());
                profileDto.setDescription(doc.getProfileOverriding().getDescription());
                dto.setProfileOverriding(profileDto);
                dto.setProfileOverridingEnabled(true);
            } else {
                dto.setProfileOverridingEnabled(false);
            }

            try {
                dto.setHlsUrl(URI.create(broadcasterConfig.getHost() + "/" + dto.getSlugName() + "/radio/stream.m3u8").toURL());
                dto.setIceCastUrl(URI.create(broadcasterConfig.getHost() + "/" + dto.getSlugName() + "/radio/icecast").toURL());
                dto.setMp3Url(URI.create(broadcasterConfig.getHost() + "/" + dto.getSlugName() + "/radio/stream.mp3").toURL());
                dto.setMixplaUrl(URI.create("https://player.mixpla.io/?radio=" + dto.getSlugName()).toURL());
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }
            dto.setArchived(doc.getArchived());
            List<BrandScriptEntryDTO> scriptDTOs = tuple.getItem4().stream()
                    .map(entry -> {
                        BrandScriptEntryDTO scriptDTO = new BrandScriptEntryDTO();
                        scriptDTO.setScriptId(entry.getScriptId());
                        scriptDTO.setUserVariables(entry.getUserVariables());
                        return scriptDTO;
                    })
                    .collect(Collectors.toList());
            dto.setScripts(scriptDTOs);
            RadioStationStatus liveStatus = tuple.getItem3().getStatus();
            dto.setStatus(liveStatus);
            return dto;
        });
    }

    private Brand buildEntity(BrandDTO dto) {
        Brand doc = new Brand();
        doc.setLocalizedName(dto.getLocalizedName());
        doc.setCountry(CountryCode.fromString(dto.getCountry()));
        doc.setArchived(dto.getArchived());
        doc.setIsTemporary(dto.getIsTemporary() != null ? dto.getIsTemporary() : 0);
        doc.setManagedBy(dto.getManagedBy());
        doc.setColor(dto.getColor());
        doc.setDescription(dto.getDescription());
        doc.setTitleFont(dto.getTitleFont());
        doc.setTimeZone(ZoneId.of(dto.getTimeZone()));
        doc.setSlugName(WebHelper.generateSlug(dto.getLocalizedName()));
        doc.setBitRate(dto.getBitRate());
        doc.setAiAgentId(dto.getAiAgentId());
        doc.setProfileId(dto.getProfileId());
        doc.setOneTimeStreamPolicy(dto.getOneTimeStreamPolicy());
        doc.setSubmissionPolicy(dto.getSubmissionPolicy());
        doc.setMessagingPolicy(dto.getMessagingPolicy());
        //doc.setPopularityRate(dto.getPopularityRate());  //cannot be changed from UI

        if (dto.getAiOverriding() != null) {
            //TODO should be validation
            AiOverriding ai = new AiOverriding();
            ai.setName(dto.getAiOverriding().getName());
            ai.setPrompt(dto.getAiOverriding().getPrompt());
            ai.setPrimaryVoice(dto.getAiOverriding().getPrimaryVoice());
            doc.setAiOverriding(ai);
        }

        if (dto.getProfileOverriding() != null) {
            ProfileOverriding profile = new ProfileOverriding();
            profile.setName(dto.getProfileOverriding().getName());
            profile.setDescription(dto.getProfileOverriding().getDescription());
            doc.setProfileOverriding(profile);
        }

        if (dto.getScripts() != null) {
            List<BrandScriptEntry> scriptEntries = dto.getScripts().stream()
                    .map(e -> new BrandScriptEntry(e.getScriptId(), e.getUserVariables()))
                    .collect(Collectors.toList());
            doc.setScripts(scriptEntries);
        }

        return doc;
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