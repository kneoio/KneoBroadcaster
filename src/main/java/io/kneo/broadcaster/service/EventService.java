package io.kneo.broadcaster.service;

import io.kneo.broadcaster.dto.event.EventDTO;
import io.kneo.broadcaster.model.Event;
import io.kneo.broadcaster.repository.EventRepository;
import io.kneo.core.dto.DocumentAccessDTO;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.IUser;
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
public class EventService extends AbstractService<Event, EventDTO> {
    private static final Logger LOGGER = LoggerFactory.getLogger(EventService.class);
    private final EventRepository repository;
    private final Validator validator;

    protected EventService() {
        super();
        this.repository = null;
        this.validator = null;
    }

    @Inject
    public EventService(UserService userService,
                        Validator validator,
                        EventRepository repository) {
        super(userService);
        this.validator = validator;
        this.repository = repository;
    }

    public Uni<List<EventDTO>> getAll(final int limit, final int offset, final IUser user) {
        assert repository != null;
        return repository.getAll(limit, offset, false, user)
                .chain(list -> {
                    if (list.isEmpty()) {
                        return Uni.createFrom().item(List.of());
                    } else {
                        List<Uni<EventDTO>> unis = list.stream()
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
    public Uni<EventDTO> getDTO(UUID uuid, IUser user, LanguageCode code) {
        assert repository != null;
        return repository.findById(uuid, user, false)
                .chain(this::mapToDTO);
    }

    public Uni<List<EventDTO>> getForBrand(String brandSlugName, int limit, final int offset, IUser user) {
        assert repository != null;
        return repository.findForBrand(brandSlugName, limit, offset, user, false)
                .chain(list -> {
                    if (list.isEmpty()) {
                        return Uni.createFrom().item(List.of());
                    } else {
                        List<Uni<EventDTO>> unis = list.stream()
                                .map(this::mapToDTO)
                                .collect(Collectors.toList());
                        return Uni.join().all(unis).andFailFast();
                    }
                });
    }

    public Uni<Integer> getCountForBrand(final String brandSlugName, final IUser user) {
        assert repository != null;
        return repository.findForBrandCount(brandSlugName, user, false);
    }

    public Uni<EventDTO> upsert(String id, EventDTO dto, IUser user) {
        assert repository != null;
        assert validator != null;
        Set<ConstraintViolation<EventDTO>> violations = validator.validate(dto);
        if (!violations.isEmpty()) {
            String errorMessage = violations.stream()
                    .map(ConstraintViolation::getMessage)
                    .collect(Collectors.joining(", "));
            return Uni.createFrom().failure(new IllegalArgumentException("Validation failed: " + errorMessage));
        }

        if (id == null) {
            Event entity = buildEntity(dto);
            return repository.insert(entity, user)
                    .chain(this::mapToDTO)
                    .onFailure().invoke(throwable -> {
                        LOGGER.error("Failed to create event", throwable);
                    });
        } else {
            Event entity = buildEntity(dto);
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

    private Uni<EventDTO> mapToDTO(Event doc) {
        return Uni.combine().all().unis(
                userService.getUserName(doc.getAuthor()),
                userService.getUserName(doc.getLastModifier())
        ).asTuple().map(tuple -> {
            EventDTO dto = new EventDTO();
            dto.setId(doc.getId());
            dto.setAuthor(tuple.getItem1());
            dto.setRegDate(doc.getRegDate());
            dto.setLastModifier(tuple.getItem2());
            dto.setLastModifiedDate(doc.getLastModifiedDate());
            //dto.setBrand(doc.getBrand());
            dto.setType(doc.getType());
            dto.setTimestampEvent(doc.getTimestampEvent());
            dto.setDescription(doc.getDescription());
            dto.setPriority(doc.getPriority());
            return dto;
        });
    }

    private Event buildEntity(EventDTO dto) {
        Event doc = new Event();
        //doc.setBrand(dto.getBrand());
        doc.setType(dto.getType());
        doc.setTimestampEvent(dto.getTimestampEvent());
        doc.setDescription(dto.getDescription());
        doc.setPriority(dto.getPriority());
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