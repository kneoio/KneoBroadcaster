package io.kneo.broadcaster.service;

import io.kneo.broadcaster.dto.ai.AiAgentDTO;
import io.kneo.broadcaster.dto.ai.ToolDTO;
import io.kneo.broadcaster.dto.ai.VoiceDTO;
import io.kneo.broadcaster.model.ai.AiAgent;
import io.kneo.broadcaster.model.ai.Tool;
import io.kneo.broadcaster.model.ai.Voice;
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
        assert repository != null;
        return repository.getAll(limit, offset, false, user)
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
        return repository.getAll(limit, offset, false, SuperUser.build());
    }

    public Uni<AiAgent> getById(UUID id, IUser user, LanguageCode language) {
        return repository.findById(id, user, false);
    }


    @Override
    public Uni<Integer> delete(String id, IUser user) {
        assert repository != null;
        return repository.delete(UUID.fromString(id), user);
    }

    @Override
    public Uni<AiAgentDTO> getDTO(UUID id, IUser user, LanguageCode language) {
        assert repository != null;
        return repository.findById(id, user, false).chain(this::mapToDTO);
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
            dto.setPreferredLang(doc.getPreferredLang());
            dto.setLlmType(doc.getLlmType());
            dto.setPrompts(doc.getPrompts());
            dto.setFillerPrompt(doc.getFillerPrompt());
            dto.setTalkativity(doc.getTalkativity());

            if (doc.getPreferredVoice() != null && !doc.getPreferredVoice().isEmpty()) {
                List<VoiceDTO> voiceDTOs = doc.getPreferredVoice().stream()
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
            if (doc.getEnabledTools() != null && !doc.getEnabledTools().isEmpty()) {
                List<ToolDTO> toolDTOs = doc.getEnabledTools().stream()
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
        AiAgent doc = new AiAgent();
        doc.setId(dto.getId());
        doc.setName(dto.getName());
        doc.setPreferredLang(dto.getPreferredLang());
        doc.setPrompts(dto.getPrompts());
        doc.setFillerPrompt(dto.getFillerPrompt());
        doc.setTalkativity(dto.getTalkativity());
        doc.setLlmType(dto.getLlmType());
        if (dto.getPreferredVoice() != null && !dto.getPreferredVoice().isEmpty()) {
            List<Voice> voices = dto.getPreferredVoice().stream()
                    .map(voiceDto -> {
                        Voice voice = new Voice();
                        voice.setId(voiceDto.getId());
                        voice.setName(voiceDto.getName());
                        return voice;
                    })
                    .collect(Collectors.toList());
            doc.setPreferredVoice(voices);
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
            doc.setEnabledTools(tools);
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