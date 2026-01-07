package io.kneo.broadcaster.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.kneo.broadcaster.model.cnst.LanguageTag;
import io.kneo.core.dto.AbstractReferenceDTO;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Setter
@Getter
public class DraftDTO  extends AbstractReferenceDTO {
    private String title;
    private String content;
    private String description;
    private LanguageTag languageTag;
    private Integer archived;
    private boolean enabled;
    private boolean master;
    private boolean locked;
    private UUID masterId;
    private double version;
}
