package io.kneo.broadcaster.service;

import io.kneo.broadcaster.dto.aiagent.AiAgentDTO;
import io.kneo.broadcaster.dto.aiagent.LanguagePreferenceDTO;
import io.kneo.broadcaster.dto.aiagent.TTSSettingDTO;
import io.kneo.broadcaster.dto.aiagent.VoiceDTO;
import io.kneo.broadcaster.model.aiagent.AiAgent;
import io.kneo.broadcaster.model.aiagent.LanguagePreference;
import io.kneo.broadcaster.model.aiagent.LlmType;
import io.kneo.broadcaster.model.aiagent.TTSSetting;
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

import java.util.ArrayList;
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

            if (doc.getCopilot() != null) dto.setCopilot(doc.getCopilot());

            if (doc.getTtsSetting() != null) {
                TTSSettingDTO ttsSettingDTO = new TTSSettingDTO();
                if (doc.getTtsSetting().getDj() != null) {
                    VoiceDTO djVoice = new VoiceDTO();
                    djVoice.setId(doc.getTtsSetting().getDj().getId());
                    djVoice.setName(doc.getTtsSetting().getDj().getName());
                    djVoice.setEngineType(doc.getTtsSetting().getDj().getEngineType());
                    ttsSettingDTO.setDj(djVoice);
                }
                if (doc.getTtsSetting().getCopilot() != null) {
                    VoiceDTO copilotVoice = new VoiceDTO();
                    copilotVoice.setId(doc.getTtsSetting().getCopilot().getId());
                    copilotVoice.setName(doc.getTtsSetting().getCopilot().getName());
                    copilotVoice.setEngineType(doc.getTtsSetting().getCopilot().getEngineType());
                    ttsSettingDTO.setCopilot(copilotVoice);
                }
                if (doc.getTtsSetting().getNewsReporter() != null) {
                    VoiceDTO newsReporterVoice = new VoiceDTO();
                    newsReporterVoice.setId(doc.getTtsSetting().getNewsReporter().getId());
                    newsReporterVoice.setName(doc.getTtsSetting().getNewsReporter().getName());
                    newsReporterVoice.setEngineType(doc.getTtsSetting().getNewsReporter().getEngineType());
                    ttsSettingDTO.setNewsReporter(newsReporterVoice);
                }
                if (doc.getTtsSetting().getWeatherReporter() != null) {
                    VoiceDTO weatherReporterVoice = new VoiceDTO();
                    weatherReporterVoice.setId(doc.getTtsSetting().getWeatherReporter().getId());
                    weatherReporterVoice.setName(doc.getTtsSetting().getWeatherReporter().getName());
                    weatherReporterVoice.setEngineType(doc.getTtsSetting().getWeatherReporter().getEngineType());
                    ttsSettingDTO.setWeatherReporter(weatherReporterVoice);
                }
                dto.setTtsSetting(ttsSettingDTO);
            }

            if (doc.getLabels() != null && !doc.getLabels().isEmpty()) {
                dto.setLabels(doc.getLabels());
            }

            return dto;
        });
    }

    private AiAgent buildEntity(AiAgentDTO dto) {
        AiAgent doc = new AiAgent();
        doc.setId(dto.getId());
        doc.setName(dto.getName());
        doc.setCopilot(dto.getCopilot());
        
        if (dto.getLabels() != null && !dto.getLabels().isEmpty()) {
            doc.setLabels(dto.getLabels());
        } else {
            doc.setLabels(new ArrayList<>());
        }
        
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

        if (dto.getTtsSetting() != null) {
            TTSSetting ttsSetting = new TTSSetting();
            if (dto.getTtsSetting().getDj() != null) {
                Voice djVoice = new Voice();
                djVoice.setId(dto.getTtsSetting().getDj().getId());
                djVoice.setName(dto.getTtsSetting().getDj().getName());
                djVoice.setEngineType(dto.getTtsSetting().getDj().getEngineType());
                ttsSetting.setDj(djVoice);
            }
            if (dto.getTtsSetting().getCopilot() != null) {
                Voice copilotVoice = new Voice();
                copilotVoice.setId(dto.getTtsSetting().getCopilot().getId());
                copilotVoice.setName(dto.getTtsSetting().getCopilot().getName());
                copilotVoice.setEngineType(dto.getTtsSetting().getCopilot().getEngineType());
                ttsSetting.setCopilot(copilotVoice);
            }
            if (dto.getTtsSetting().getNewsReporter() != null) {
                Voice newsReporterVoice = new Voice();
                newsReporterVoice.setId(dto.getTtsSetting().getNewsReporter().getId());
                newsReporterVoice.setName(dto.getTtsSetting().getNewsReporter().getName());
                newsReporterVoice.setEngineType(dto.getTtsSetting().getNewsReporter().getEngineType());
                ttsSetting.setNewsReporter(newsReporterVoice);
            }
            if (dto.getTtsSetting().getWeatherReporter() != null) {
                Voice weatherReporterVoice = new Voice();
                weatherReporterVoice.setId(dto.getTtsSetting().getWeatherReporter().getId());
                weatherReporterVoice.setName(dto.getTtsSetting().getWeatherReporter().getName());
                weatherReporterVoice.setEngineType(dto.getTtsSetting().getWeatherReporter().getEngineType());
                ttsSetting.setWeatherReporter(weatherReporterVoice);
            }
            doc.setTtsSetting(ttsSetting);
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
