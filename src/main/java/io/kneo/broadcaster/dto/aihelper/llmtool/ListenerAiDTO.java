package io.kneo.broadcaster.dto.aihelper.llmtool;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.kneo.core.dto.AbstractReferenceDTO;
import io.kneo.core.localization.LanguageCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.EnumMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Setter
@Getter
@SuperBuilder
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ListenerAiDTO extends AbstractReferenceDTO {
    String telegramName;
    private String country;
    private EnumMap<LanguageCode, String> localizedName = new EnumMap<>(LanguageCode.class);
    private EnumMap<LanguageCode, Set<String>> nickName = new EnumMap<>(LanguageCode.class);
    private String slugName;
    private List<List<String>> listenerOf;
    private List<UUID> labels;

}
