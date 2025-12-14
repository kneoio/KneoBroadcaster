package io.kneo.broadcaster.dto.stream;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import io.kneo.core.localization.LanguageCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.UUID;

@Setter
@Getter
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OneTimeStreamDTO {

    private UUID id;
    private String slugName;
    private EnumMap<LanguageCode, String> localizedName = new EnumMap<>(LanguageCode.class);
    private String timeZone;
    private long bitRate;
    private RadioStationStatus status = RadioStationStatus.OFF_LINE;
    private UUID baseBrandId;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;

}
