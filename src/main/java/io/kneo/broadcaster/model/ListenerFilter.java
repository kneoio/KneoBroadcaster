package io.kneo.broadcaster.model;

import io.kneo.officeframe.cnst.CountryCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Setter
@Getter
@NoArgsConstructor
public class ListenerFilter {
    private boolean activated = false;
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