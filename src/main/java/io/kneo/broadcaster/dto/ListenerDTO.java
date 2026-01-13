package io.kneo.broadcaster.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.kneo.core.dto.AbstractReferenceDTO;
import io.kneo.core.dto.validation.ValidCountry;
import io.kneo.core.localization.LanguageCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
public class ListenerDTO extends AbstractReferenceDTO {
    private long userId;
    private String telegramName;
    private String email;
    @NotNull(message = "Country is required")
    @NotBlank(message = "Country cannot be empty")
    @ValidCountry(message = "Country is not supported")
    private String country;

    private EnumMap<LanguageCode, Set<String>> nickName = new EnumMap<>(LanguageCode.class);
    private String slugName;
    private Integer archived;
    private List<UUID> listenerOf;
    private String listenerType;

}