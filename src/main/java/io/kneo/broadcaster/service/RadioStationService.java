package io.kneo.broadcaster.service;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.model.stats.BrandAgentStats;
import io.kneo.broadcaster.service.stream.RadioStationPool;
import io.kneo.broadcaster.dto.ProfileDTO;
import io.kneo.broadcaster.dto.RadioStationDTO;
import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.repository.RadioStationRepository;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.IUser;
import io.kneo.core.model.user.SuperUser;
import io.kneo.core.service.AbstractService;
import io.kneo.core.service.UserService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class RadioStationService extends AbstractService<RadioStation, RadioStationDTO> {
    private static final Logger LOGGER = LoggerFactory.getLogger(RadioStationService.class);

    private final RadioStationRepository repository;

    BroadcasterConfig broadcasterConfig;

    RadioStationPool radiostationPool;

    private final ProfileService profileService;

    @Inject
    public RadioStationService(
            UserService userService,
            RadioStationRepository repository,
            RadioStationPool radiostationPool,
            BroadcasterConfig broadcasterConfig,
            ProfileService profileService
    ) {
        super(userService);
        this.repository = repository;
        this.radiostationPool = radiostationPool;
        this.broadcasterConfig = broadcasterConfig;
        this.profileService = profileService;
    }

    public Uni<List<RadioStationDTO>> getAll(final int limit, final int offset, final IUser user)  {
        assert repository != null;
        return repository.getAll(limit, offset, user)
                .chain(list -> {
                    if (list.isEmpty()) {
                        return Uni.createFrom().item(List.of());
                    } else {
                        List<Uni<RadioStationDTO>> unis = list.stream()
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

    public Uni<List<RadioStation>> getAll(final int limit, final int offset)  {
        return repository.getAll(limit, offset, SuperUser.build());
    }

    public Uni<RadioStation> getById(UUID id, IUser user, LanguageCode language) {
        return repository.findById(id);
    }

    public Uni<RadioStation> findByBrandName(String name) {
        return repository.findByBrandName(name);
    }



    @Override
    public Uni<Integer> delete(String id, IUser user) {
        assert repository != null;
        return repository.delete(UUID.fromString(id));
    }

    @Override
    public Uni<RadioStationDTO> getDTO(UUID id, IUser user, LanguageCode language) {
        assert repository != null;
        return repository.findById(id).chain(this::mapToDTO);
    }

    public Uni<BrandAgentStats> getStats(String stationName) {
        return repository.findStationStatsByStationName(stationName);
    }

    public Uni<RadioStationDTO> upsert(String id, RadioStationDTO dto, IUser user, LanguageCode code) {
        assert repository != null;
        RadioStation entity = buildEntity(dto);
        if (id == null) {
            return repository.insert(entity).chain(this::mapToDTO);
        } else {
            return repository.update(UUID.fromString(id), entity).chain(this::mapToDTO);
        }
    }

    private Uni<RadioStationDTO> mapToDTO(RadioStation doc) {
        Uni<ProfileDTO> profileUni;
        if (doc.getProfileId() != null) {
            profileUni = profileService.getDTO(doc.getProfileId(), SuperUser.build(), LanguageCode.ENG);
        } else {
            profileUni = Uni.createFrom().item((ProfileDTO) null);
        }

        return Uni.combine().all().unis(
                userService.getUserName(doc.getAuthor()),
                userService.getUserName(doc.getLastModifier()),
                radiostationPool.checkStatus(doc.getSlugName()),
                profileUni
        ).asTuple().map(tuple -> {
            RadioStationDTO dto = new RadioStationDTO();
            dto.setId(doc.getId());
            dto.setAuthor(tuple.getItem1());
            dto.setRegDate(doc.getRegDate());
            dto.setLastModifier(tuple.getItem2());
            dto.setLastModifiedDate(doc.getLastModifiedDate());
            dto.setCountry(doc.getCountry());
            dto.setSlugName(doc.getSlugName());
            dto.setManagedBy(doc.getManagedBy());
            dto.setProfile(tuple.getItem4());

            try {
                dto.setUrl(new URL(broadcasterConfig.getHost() + "/" + dto.getSlugName() + "/radio/stream.m3u8"));
                dto.setIceCastUrl(new URL(broadcasterConfig.getHost() + "/" + dto.getSlugName() + "/radio/icecast "));
                dto.setActionUrl(new URL(broadcasterConfig.getHost() + "/" + dto.getSlugName() + "/api/queue/action"));
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }
            dto.setArchived(doc.getArchived());
            dto.setStatus(tuple.getItem3().getStatus());
            return dto;
        });
    }

    private RadioStation buildEntity(RadioStationDTO dto) {
        RadioStation entity = new RadioStation();

        return entity;
    }


}