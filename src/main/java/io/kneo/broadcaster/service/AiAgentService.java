package io.kneo.broadcaster.service;

import io.kneo.broadcaster.dto.aiagent.AiAgentDTO;
import io.kneo.broadcaster.dto.aiagent.LanguagePreferenceDTO;
import io.kneo.broadcaster.dto.aiagent.VoiceDTO;
import io.kneo.broadcaster.model.aiagent.AiAgent;
import io.kneo.broadcaster.model.aiagent.LanguagePreference;
import io.kneo.broadcaster.model.aiagent.LlmType;
import io.kneo.broadcaster.model.aiagent.SearchEngineType;
import io.kneo.broadcaster.model.aiagent.Voice;
import io.kneo.broadcaster.model.cnst.LanguageTag;
import io.kneo.broadcaster.repository.AiAgentRepository;
import io.kneo.core.dto.DocumentAccessDTO;
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
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class AiAgentService extends AbstractService<AiAgent, AiAgentDTO> {
    private static final Logger LOGGER = LoggerFactory.getLogger(AiAgentService.class);

    private final AiAgentRepository repository;

    @Inject
    public AiAgentService(
            UserService userService,
            AiAgentRepository repository
    ) {
        super(userService);
        this.repository = repository;
    }

    public Uni<List<AiAgentDTO>> getAll(final int limit, final int offset, final IUser user) {
        return repository.getAll(limit, offset, false, user)
                .chain(list -> {
                    if (list.isEmpty()) return Uni.createFrom().item(List.of());
                    List<Uni<AiAgentDTO>> unis = list.stream()
                            .map(this::mapToDTO)
                            .collect(Collectors.toList());
                    return Uni.join().all(unis).andFailFast();
                });
    }

    public Uni<Integer> getAllCount(final IUser user) {
        return repository.getAllCount(user, false);
    }

    public Uni<List<AiAgent>> getAll(final int limit, final int offset) {
        return repository.getAll(limit, offset, false, SuperUser.build());
    }

    public Uni<AiAgent> getById(UUID id, IUser user, LanguageCode language) {
        return repository.findById(id, user, false);
    }

    @Override
    public Uni<Integer> delete(String id, IUser user) {
        return repository.delete(UUID.fromString(id), user);
    }

    @Override
    public Uni<AiAgentDTO> getDTO(UUID id, IUser user, LanguageCode language) {
        return repository.findById(id, user, false).chain(this::mapToDTO);
    }

    public Uni<AiAgentDTO> upsert(String id, AiAgentDTO dto, IUser user, LanguageCode code) {
        AiAgent entity = buildEntity(dto);
        if (id == null || id.isEmpty()) {
            return repository.insert(entity, user).chain(this::mapToDTO);
        } else {
            return repository.update(UUID.fromString(id), entity, user).chain(this::mapToDTO);
        }
    }

    private Uni<AiAgentDTO> mapToDTO(AiAgent doc) {
        return Uni.combine().all().unis(
                userService.getUserName(doc.getAuthor()),
                userService.getUserName(doc.getLastModifier())
        ).asTuple().map(tuple -> {
            AiAgentDTO dto = new AiAgentDTO();
            dto.setId(doc.getId());
            dto.setAuthor(tuple.getItem1());
            dto.setRegDate(doc.getRegDate());
            dto.setLastModifier(tuple.getItem2());
            dto.setLastModifiedDate(doc.getLastModifiedDate());
            dto.setName(doc.getName());
            
            if (doc.getPreferredLang() != null && !doc.getPreferredLang().isEmpty()) {
                List<LanguagePreferenceDTO> langPrefDTOs = doc.getPreferredLang().stream()
                        .map(pref -> new LanguagePreferenceDTO(pref.getLanguageTag().tag(), pref.getWeight()))
                        .toList();
                dto.setPreferredLang(langPrefDTOs);
            }
            
            dto.setLlmType(doc.getLlmType().name());
            dto.setSearchEngineType(doc.getSearchEngineType().name());

            if (doc.getPrimaryVoice() != null && !doc.getPrimaryVoice().isEmpty()) {
                List<VoiceDTO> voiceDTOs = doc.getPrimaryVoice().stream()
                        .map(voice -> {
                            VoiceDTO voiceDTO = new VoiceDTO();
                            voiceDTO.setId(voice.getId());
                            voiceDTO.setName(voice.getName());
                            return voiceDTO;
                        })
                        .toList();
                dto.setPrimaryVoice(voiceDTOs);
            }

            if (doc.getCopilot() != null) dto.setCopilot(doc.getCopilot());

            return dto;
        });
    }

    private AiAgent buildEntity(AiAgentDTO dto) {
        AiAgent doc = new AiAgent();
        doc.setId(dto.getId());
        doc.setName(dto.getName());
        doc.setCopilot(dto.getCopilot());
        
        if (dto.getPreferredLang() != null && !dto.getPreferredLang().isEmpty()) {
            List<LanguagePreference> langPrefs = dto.getPreferredLang().stream()
                    .map(prefDto -> {
                            LanguagePreference pref = new LanguagePreference();
                            pref.setWeight(prefDto.getWeight());
                            pref.setLanguageTag(LanguageTag.fromTag(prefDto.getLanguageTag()));
                            return pref;
                    })
                    .collect(Collectors.toList());
            doc.setPreferredLang(langPrefs);
        }
        
        doc.setLlmType(LlmType.valueOf(dto.getLlmType()));
        
        if (dto.getSearchEngineType() != null) {
            doc.setSearchEngineType(SearchEngineType.valueOf(dto.getSearchEngineType()));
        }

        if (dto.getPrimaryVoice() != null && !dto.getPrimaryVoice().isEmpty()) {
            List<Voice> voices = dto.getPrimaryVoice().stream()
                    .map(voiceDto -> {
                        Voice voice = new Voice();
                        voice.setId(voiceDto.getId());
                        voice.setName(voiceDto.getName());
                        return voice;
                    })
                    .collect(Collectors.toList());
            doc.setPrimaryVoice(voices);
        }

        return doc;
    }

    public Uni<List<DocumentAccessDTO>> getDocumentAccess(UUID documentId, IUser user) {
        return repository.getDocumentAccessInfo(documentId, user)
                .onItem().transform(accessInfoList ->
                        accessInfoList.stream()
                                .map(this::mapToDocumentAccessDTO)
                                .collect(Collectors.toList())
                );
    }
}
