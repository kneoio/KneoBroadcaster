package io.kneo.broadcaster.model.radiostation;

import io.kneo.broadcaster.model.cnst.ExternalProviderType;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
public class ExternalSource {
    private ExternalProviderType providerType;
    private double probability;
    private Map<String, String> parameters;
}
