package io.kneo.broadcaster.service;

import io.kneo.broadcaster.dto.RadioStationDTO;
import io.kneo.broadcaster.model.RadioStation;
import io.kneo.broadcaster.repository.RadioStationRepository;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.IUser;
import io.kneo.core.service.AbstractService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class RadioStationService extends AbstractService<RadioStation, RadioStationDTO> {
    private static final Logger LOGGER = LoggerFactory.getLogger(RadioStationService.class);

    private final RadioStationRepository repository;

    @Inject
    public RadioStationService(RadioStationRepository repository) {
        super(null, null);
        this.repository = repository;
    }

    public Uni<List<RadioStation>> getAll(final int limit, final int offset) {
        assert repository != null;
        return repository.getAll(limit, offset);
    }

    public Uni<Integer> getAllCount(final IUser user) {
        assert repository != null;
        return repository.getAllCount(user);
    }

    @Override
    public Uni<RadioStationDTO> getDTO(UUID id, IUser user, LanguageCode language) {
        assert repository != null;
        return repository.findById(id).chain(this::mapToDTO);
    }

    @Override
    public Uni<Integer> delete(String id, IUser user) {
        assert repository != null;
        return repository.delete(UUID.fromString(id));
    }

    public Uni<RadioStationDTO> upsert(String id, RadioStation dto) {
        assert repository != null;
        RadioStation entity = buildEntity(dto);
        if (id == null) {
            return repository.insert(entity).chain(this::mapToDTO);
        } else {
            return repository.update(UUID.fromString(id), entity).chain(this::mapToDTO);
        }
    }

    private Uni<RadioStationDTO> mapToDTO(RadioStation doc) {
        return Uni.createFrom().item(() -> {
            RadioStationDTO dto = new RadioStationDTO();
            dto.setId(doc.getId());

            return dto;
        });
    }

    private RadioStation buildEntity(RadioStation dto) {
        RadioStation entity = new RadioStation();

        return entity;
    }
}