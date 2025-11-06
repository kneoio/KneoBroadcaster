package io.kneo.broadcaster.service;

import io.kneo.broadcaster.ai.DraftFactory;
import io.kneo.broadcaster.dto.DraftDTO;
import io.kneo.broadcaster.dto.ai.DraftTestDTO;
import io.kneo.broadcaster.dto.aihelper.SongIntroductionDTO;
import io.kneo.broadcaster.dto.filter.DraftFilterDTO;
import io.kneo.broadcaster.model.Draft;
import io.kneo.broadcaster.model.cnst.EventType;
import io.kneo.broadcaster.model.cnst.MemoryType;
import io.kneo.broadcaster.repository.DraftRepository;
import io.kneo.broadcaster.service.soundfragment.SoundFragmentService;
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

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class DraftService extends AbstractService<Draft, DraftDTO> {
    private static final Logger LOGGER = LoggerFactory.getLogger(DraftService.class);

    private final DraftRepository repository;
    private final DraftFactory draftFactory;
    private final SoundFragmentService soundFragmentService;
    private final AiAgentService aiAgentService;
    private final RadioStationService radioStationService;
    private final MemoryService memoryService;

    @Inject
    public DraftService(UserService userService, DraftRepository repository, DraftFactory draftFactory,
                        SoundFragmentService soundFragmentService, AiAgentService aiAgentService,
                        RadioStationService radioStationService, MemoryService memoryService) {
        super(userService);
        this.repository = repository;
        this.draftFactory = draftFactory;
        this.soundFragmentService = soundFragmentService;
        this.aiAgentService = aiAgentService;
        this.radioStationService = radioStationService;
        this.memoryService = memoryService;
    }

    public Uni<List<Draft>> getAll() {
        return repository.getAll(0, 0, false, SuperUser.build(), null);
    }

    public Uni<List<DraftDTO>> getAll(final int limit, final int offset, final IUser user, final DraftFilterDTO filter) {
        return repository.getAll(limit, offset, false, user, filter)
                .chain(list -> {
                    if (list.isEmpty()) {
                        return Uni.createFrom().item(List.of());
                    } else {
                        List<Uni<DraftDTO>> unis = list.stream()
                                .map(this::mapToDTO)
                                .collect(Collectors.toList());
                        return Uni.join().all(unis).andFailFast();
                    }
                });
    }

    public Uni<Integer> getAllCount(final IUser user, final DraftFilterDTO filter) {
        return repository.getAllCount(user, false, filter);
    }

    public Uni<Draft> getById(UUID id, IUser user) {
        return repository.findById(id, user, true);
    }

    @Override
    public Uni<Integer> delete(String id, IUser user) {
        return repository.archive(UUID.fromString(id), user);
    }

    @Override
    public Uni<DraftDTO> getDTO(UUID id, IUser user, LanguageCode language) {
        return repository.findById(id, user, false).chain(this::mapToDTO);
    }

    public Uni<DraftDTO> upsert(String id, DraftDTO dto, IUser user, LanguageCode code) {
        Draft entity = buildEntity(dto);
        Uni<Draft> saveOperation = (id == null)
                ? repository.insert(entity, user)
                : repository.update(UUID.fromString(id), entity, user);
        return saveOperation.chain(this::mapToDTO);
    }

    public Uni<Integer> archive(String id, IUser user) {
        return repository.archive(UUID.fromString(id), user);
    }

    private Uni<DraftDTO> mapToDTO(Draft doc) {
        return Uni.combine().all().unis(
                userService.getUserName(doc.getAuthor()),
                userService.getUserName(doc.getLastModifier())
        ).asTuple().map(tuple -> {
            DraftDTO dto = new DraftDTO();
            dto.setId(doc.getId());
            dto.setAuthor(tuple.getItem1());
            dto.setRegDate(doc.getRegDate());
            dto.setLastModifier(tuple.getItem2());
            dto.setLastModifiedDate(doc.getLastModifiedDate());
            dto.setTitle(doc.getTitle());
            dto.setContent(doc.getContent());
            dto.setLanguageCode(doc.getLanguageCode());
            dto.setArchived(doc.getArchived());
            dto.setEnabled(doc.isEnabled());
            dto.setMaster(doc.isMaster());
            dto.setLocked(doc.isLocked());
            return dto;
        });
    }

    private Draft buildEntity(DraftDTO dto) {
        Draft doc = new Draft();
        doc.setTitle(dto.getTitle());
        doc.setContent(dto.getContent());
        doc.setLanguageCode(dto.getLanguageCode());
        doc.setArchived(dto.getArchived() != null ? dto.getArchived() : 0);
        doc.setEnabled(dto.isEnabled());
        doc.setMaster(dto.isMaster());
        doc.setLocked(dto.isLocked());
        return doc;
    }

    public Uni<String> testDraft(DraftTestDTO dto, IUser user) {
        return radioStationService.getById(dto.getStationId(), user)
                .chain(station -> {
                    String brand = station.getSlugName();
                    return memoryService.addMessage(brand, "John", "Can you play some rock music?")
                            .chain(id1 -> memoryService.addMessage(brand, "Sarah", "I love this station!"))
                            .chain(id2 -> memoryService.addEvent(brand, EventType.WEATHER, "2025-11-02T21:00:00Z", "Sunny weather, 25°C"))
                            .chain(id4 -> {
                                SongIntroductionDTO historyDto = new SongIntroductionDTO();
                                historyDto.setRelevantSoundFragmentId(dto.getSongId().toString());
                                historyDto.setArtist("The Beatles");
                                historyDto.setTitle("Hey Jude");
                                String timestamp = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
                                historyDto.setIntroSpeech("Here's a classic from The Beatles that never gets old! [Played at " + timestamp + "]");
                                return memoryService.updateHistory(brand, historyDto)
                                        .chain(result -> memoryService.commitHistory(brand, dto.getSongId()));
                            })
                            .chain(ignored -> soundFragmentService.getById(dto.getSongId(), user))
                            .chain(song -> aiAgentService.getById(dto.getAgentId(), user, LanguageCode.en)
                                    .chain(agent -> memoryService.getByType(
                                                    brand,
                                                    MemoryType.MESSAGE.name(),
                                                    MemoryType.EVENT.name(),
                                                    MemoryType.CONVERSATION_HISTORY.name()
                                            )
                                            .chain(memoryData ->
                                                    draftFactory.createDraftFromCode(
                                                            dto.getCode(),
                                                            song,
                                                            agent,
                                                            station,
                                                            memoryData
                                                    )
                                            ))
                            );
                });
    }
}
