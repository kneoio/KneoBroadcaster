package io.kneo.broadcaster.service;

import io.kneo.broadcaster.dto.DraftDTO;
import io.kneo.broadcaster.dto.agentrest.DraftTestReqDTO;
import io.kneo.broadcaster.dto.filter.DraftFilterDTO;
import io.kneo.broadcaster.model.Draft;
import io.kneo.broadcaster.model.aiagent.LanguagePreference;
import io.kneo.broadcaster.repository.draft.DraftRepository;
import io.kneo.broadcaster.service.live.DraftFactory;
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

import java.util.List;
import java.util.Random;
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

    @Inject
    public DraftService(UserService userService, DraftRepository repository, DraftFactory draftFactory,
                        SoundFragmentService soundFragmentService, AiAgentService aiAgentService,
                        RadioStationService radioStationService) {
        super(userService);
        this.repository = repository;
        this.draftFactory = draftFactory;
        this.soundFragmentService = soundFragmentService;
        this.aiAgentService = aiAgentService;
        this.radioStationService = radioStationService;
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

    public Uni<List<Draft>> getByIds(List<UUID> ids, IUser user) {
        List<Uni<Draft>> draftUnis = ids.stream()
                .map(id -> repository.findById(id, user, false)
                        .onFailure().recoverWithNull())
                .collect(Collectors.toList());
        
        return Uni.join().all(draftUnis).andCollectFailures()
                .map(drafts -> drafts.stream()
                        .filter(java.util.Objects::nonNull)
                        .collect(Collectors.toList()));
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

    public Uni<Draft> insert(Draft entity, IUser user) {
        return repository.insert(entity, user);
    }

    public Uni<Draft> update(UUID id, Draft entity, IUser user) {
        return repository.update(id, entity, user);
    }

    public Uni<Integer> archive(String id, IUser user) {
        return repository.archive(UUID.fromString(id), user);
    }

    public Uni<Draft> findByMasterAndLanguage(UUID masterId, LanguageCode languageCode, boolean includeArchived) {
        return repository.findByMasterAndLanguage(masterId, languageCode, includeArchived);
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
            dto.setMasterId(doc.getMasterId());
            dto.setVersion(doc.getVersion());
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
        doc.setMasterId(dto.getMasterId());
        doc.setVersion(dto.getVersion());
        return doc;
    }

    public Uni<String> testDraft(DraftTestReqDTO dto, IUser user) {
        return radioStationService.getById(dto.getStationId(), user)
                .chain(station -> soundFragmentService.getById(dto.getSongId(), user)
                        .chain(song -> aiAgentService.getById(dto.getAgentId(), user, LanguageCode.en)
                                .chain(agent -> {
                                    LanguageCode selectedLanguage = selectLanguageByWeight(agent);
                                    return draftFactory.createDraftFromCode(
                                            dto.getCode(),
                                            song,
                                            agent,
                                            station,
                                            selectedLanguage
                                    );
                                })
                        )
                );
    }

    private LanguageCode selectLanguageByWeight(io.kneo.broadcaster.model.aiagent.AiAgent agent) {
        List<LanguagePreference> preferences = agent.getPreferredLang();
        if (preferences == null || preferences.isEmpty()) {
            LOGGER.warn("Agent '{}' has no language preferences, defaulting to English", agent.getName());
            return LanguageCode.en;
        }

        if (preferences.size() == 1) {
            return preferences.get(0).getCode();
        }

        double totalWeight = preferences.stream()
                .mapToDouble(LanguagePreference::getWeight)
                .sum();

        if (totalWeight <= 0) {
            LOGGER.warn("Agent '{}' has invalid weights (total <= 0), using first language", agent.getName());
            return preferences.get(0).getCode();
        }

        Random random = new Random();
        double randomValue = random.nextDouble() * totalWeight;
        double cumulativeWeight = 0;
        for (LanguagePreference pref : preferences) {
            cumulativeWeight += pref.getWeight();
            if (randomValue <= cumulativeWeight) {
                return pref.getCode();
            }
        }

        return preferences.get(0).getCode();
    }
}
