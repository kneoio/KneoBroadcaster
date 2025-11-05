package io.kneo.broadcaster.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.kneo.core.dto.AbstractReferenceDTO;
import io.kneo.core.localization.LanguageCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Setter
@Getter
@NoArgsConstructor
public class DraftDTO  extends AbstractReferenceDTO {
    private String title;
    private String content;
    private LanguageCode languageCode;
    private Integer archived;
    private boolean enabled;
    private boolean isMaster;
    private boolean locked;
}
