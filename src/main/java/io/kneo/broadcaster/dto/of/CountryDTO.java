package io.kneo.broadcaster.dto.of;

import lombok.Getter;

@Getter
public class CountryDTO {
    private final int code;
    private final String isoCode;
    private final String name;

    public CountryDTO(int code, String isoCode, String name) {
        this.code = code;
        this.isoCode = isoCode;
        this.name = name;
    }

}
