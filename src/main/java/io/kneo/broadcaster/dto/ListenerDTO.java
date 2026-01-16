package io.kneo.broadcaster.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.kneo.core.dto.AbstractReferenceDTO;
import io.kneo.core.localization.LanguageCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Setter
@Getter
@SuperBuilder
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ListenerDTO extends AbstractReferenceDTO {
    private long userId;
    private EnumMap<LanguageCode, Set<String>> nickName = new EnumMap<>(LanguageCode.class);
    private Map<String, String> userData = new HashMap<>();
    private String slugName;
    private Integer archived;
    private List<ListenerOfBrandDTO> listenerOf;

}