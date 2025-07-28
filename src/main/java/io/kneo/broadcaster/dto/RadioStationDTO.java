package io.kneo.broadcaster.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.broadcaster.dto.scheduler.ScheduleDTO;
import io.kneo.broadcaster.model.cnst.ManagedBy;
import io.kneo.core.dto.AbstractDTO;
import io.kneo.core.dto.validation.ValidCountry;
import io.kneo.core.dto.validation.ValidLocalizedName;
import io.kneo.core.localization.LanguageCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.net.URL;
import java.util.EnumMap;
import java.util.UUID;

@Setter
@Getter
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RadioStationDTO extends AbstractDTO {
    @NotNull(message = "Localized name is required")
    @ValidLocalizedName(
            minLength = 1,
            maxLength = 255,
            allowEmptyMap = false,
            requireDefaultLanguage = true,
            defaultLanguage = LanguageCode.en,
            message = "Invalid localized name format"
    )
    private EnumMap<LanguageCode, String> localizedName = new EnumMap<>(LanguageCode.class);
    private String slugName;
    @NotNull(message = "Country is required")
    @NotBlank(message = "Country cannot be empty")
    @ValidCountry(message = "It is not available for the country")
    private String country;
    @NotNull
    private ManagedBy managedBy;
    private URL hlsUrl;
    private URL iceCastUrl;
    private URL mixplaUrl;
    @NotBlank
    @Pattern(regexp = "^[A-Za-z]+/[A-Za-z_]+$", message = "Invalid timezone format")
    private String timeZone;
    private String color;
    private String description;
    private ScheduleDTO schedule;
    private boolean aiControlAllowed;
    private Integer archived;
    private RadioStationStatus status = RadioStationStatus.OFF_LINE;
    private UUID aiAgentId;
    private UUID profileId;
}