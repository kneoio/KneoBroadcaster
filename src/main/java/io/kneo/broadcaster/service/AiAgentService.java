package io.kneo.broadcaster.service;

import io.kneo.broadcaster.dto.ai.AiAgentDTO;
import io.kneo.broadcaster.dto.ai.ToolDTO;
import io.kneo.broadcaster.dto.ai.VoiceDTO;
import io.kneo.broadcaster.model.ai.AiAgent;
import io.kneo.broadcaster.model.ai.Tool;
import io.kneo.broadcaster.model.ai.Voice;
import io.kneo.broadcaster.repository.AiAgentRepository;
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
        assert repository != null;
        return repository.getAll(limit, offset, user)
                .chain(list -> {
                    if (list.isEmpty()) {
                        return Uni.createFrom().item(List.of());
                    } else {
                        List<Uni<AiAgentDTO>> unis = list.stream()
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

    public Uni<List<AiAgent>> getAll(final int limit, final int offset) {
        return repository.getAll(limit, offset, SuperUser.build());
    }

    public Uni<AiAgent> getById(UUID id, IUser user, LanguageCode language) {
        return repository.findById(id);
    }

    public Uni<AiAgent> findByName(String name) {
        return repository.findByName(name);
    }

    public Uni<List<AiAgentDTO>> findByPreferredLang(LanguageCode lang, IUser user) {
        return repository.findByPreferredLang(lang)
                .chain(list -> {
                    if (list.isEmpty()) {
                        return Uni.createFrom().item(List.of());
                    } else {
                        List<Uni<AiAgentDTO>> unis = list.stream()
                                .map(this::mapToDTO)
                                .collect(Collectors.toList());
                        return Uni.join().all(unis).andFailFast();
                    }
                });
    }

    public Uni<List<AiAgentDTO>> getActiveAgents(IUser user) {
        return repository.findActiveAgents()
                .chain(list -> {
                    if (list.isEmpty()) {
                        return Uni.createFrom().item(List.of());
                    } else {
                        List<Uni<AiAgentDTO>> unis = list.stream()
                                .map(this::mapToDTO)
                                .collect(Collectors.toList());
                        return Uni.join().all(unis).andFailFast();
                    }
                });
    }

    @Override
    public Uni<Integer> delete(String id, IUser user) {
        assert repository != null;
        return repository.delete(UUID.fromString(id));
    }

    public Uni<Integer> softDelete(String id, IUser user) {
        assert repository != null;
        return repository.softDelete(UUID.fromString(id), user);
    }

    @Override
    public Uni<AiAgentDTO> getDTO(UUID id, IUser user, LanguageCode language) {
        assert repository != null;
        return repository.findById(id).chain(this::mapToDTO);
    }

    public Uni<AiAgentDTO> upsert(String id, AiAgentDTO dto, IUser user, LanguageCode code) {
        assert repository != null;
        AiAgent entity = buildEntity(dto);
        if (id == null || id.isEmpty()) {
            return repository.insert(entity, user).chain(this::mapToDTO);
        } else {
            return repository.update(UUID.fromString(id), entity, user).chain(this::mapToDTO);
        }
    }

    private Uni<AiAgentDTO> mapToDTO(AiAgent agent) {
        return Uni.combine().all().unis(
                userService.getUserName(agent.getAuthor()),
                userService.getUserName(agent.getLastModifier())
        ).asTuple().map(tuple -> {
            AiAgentDTO dto = new AiAgentDTO();
            dto.setId(agent.getId());
            dto.setAuthor(tuple.getItem1());
            dto.setRegDate(agent.getRegDate());
            dto.setLastModifier(tuple.getItem2());
            dto.setLastModifiedDate(agent.getLastModifiedDate());
            dto.setName(agent.getName());
            dto.setPreferredLang(agent.getPreferredLang());
            dto.setMainPrompt(agent.getMainPrompt());
            dto.setFillerPrompt(agent.getFillerPrompt());
            dto.setTalkativity(agent.getTalkativity());

            if (agent.getPreferredVoice() != null && !agent.getPreferredVoice().isEmpty()) {
                List<VoiceDTO> voiceDTOs = agent.getPreferredVoice().stream()
                        .map(voice -> {
                            VoiceDTO voiceDTO = new VoiceDTO();
                            voiceDTO.setId(voice.getId());
                            voiceDTO.setName(voice.getName());
                            return voiceDTO;
                        })
                        .toList();
                dto.setPreferredVoice(voiceDTOs);
            }

            // Map enabled tools
            if (agent.getEnabledTools() != null && !agent.getEnabledTools().isEmpty()) {
                List<ToolDTO> toolDTOs = agent.getEnabledTools().stream()
                        .map(tool -> {
                            ToolDTO toolDTO = new ToolDTO();
                            toolDTO.setName(tool.getName());
                            toolDTO.setVariableName(tool.getVariableName());
                            toolDTO.setDescription(tool.getDescription());
                            return toolDTO;
                        })
                        .toList();
                dto.setEnabledTools(toolDTOs);
            }

            return dto;
        });
    }

    private AiAgent buildEntity(AiAgentDTO dto) {
        AiAgent entity = new AiAgent();
        entity.setId(dto.getId());
        entity.setName(dto.getName());
        entity.setPreferredLang(dto.getPreferredLang());
        entity.setMainPrompt(dto.getMainPrompt());

        // Build preferred voices
        if (dto.getPreferredVoice() != null && !dto.getPreferredVoice().isEmpty()) {
            List<Voice> voices = dto.getPreferredVoice().stream()
                    .map(voiceDto -> {
                        Voice voice = new Voice();
                        voice.setId(voiceDto.getId());
                        voice.setName(voiceDto.getName());
                        return voice;
                    })
                    .collect(Collectors.toList());
            entity.setPreferredVoice(voices);
        }

        if (dto.getEnabledTools() != null && !dto.getEnabledTools().isEmpty()) {
            List<Tool> tools = dto.getEnabledTools().stream()
                    .map(toolDto -> {
                        Tool tool = new Tool();
                        tool.setName(toolDto.getName());
                        tool.setVariableName(toolDto.getVariableName());
                        tool.setDescription(toolDto.getDescription());
                        return tool;
                    })
                    .collect(Collectors.toList());
            entity.setEnabledTools(tools);
        }

        return entity;
    }
}