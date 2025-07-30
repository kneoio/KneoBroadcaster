package io.kneo.broadcaster.dto;

import io.kneo.officeframe.cnst.CountryCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

@Setter
@Getter
@NoArgsConstructor
public class ListenerFilterDTO {
    private boolean activated = false;

    @NotEmpty(message = "Countries list cannot be empty when provided")
    private List<CountryCode> countries;

    public boolean isActivated() {
        if (activated) {
            return true;
        }
        return hasAnyFilter();
    }

    private boolean hasAnyFilter() {
        if (countries != null && !countries.isEmpty()) {
            return true;
        }
        return false;
    }
}