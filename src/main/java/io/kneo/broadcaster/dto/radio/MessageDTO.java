package io.kneo.broadcaster.dto.radio;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.kneo.officeframe.cnst.CountryCode;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.List;
import java.util.UUID;

@Setter
@Getter
@SuperBuilder
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MessageDTO {
    @NotBlank
    private String confirmationCode;
    @NotBlank
    private String from;
    @NotBlank
    private String content;
    private List<UUID> representedInBrands;
    private CountryCode country;
    private String email;
    private String agreedAt;
    private String ipAddress;
    private String userAgent;
    private String termsText;
    private String agreementVersion;

    public String toString() {
        return String.format("%s|%s...", from, content.substring(0, 10));
    }

}