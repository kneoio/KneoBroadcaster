package io.kneo.broadcaster.dto;

import io.kneo.broadcaster.model.Genre;
import io.kneo.broadcaster.model.cnst.AnnouncementFrequency;
import io.kneo.core.dto.AbstractDTO;
import io.kneo.core.localization.LanguageCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Setter
@Getter
@NoArgsConstructor
public class ProfileDTO extends AbstractDTO {
    private String name;
    private String description;
    private List<Genre> allowedGenres;
    private AnnouncementFrequency announcementFrequency;
    private boolean explicitContent;
    private LanguageCode language;
    private Integer archived;
}