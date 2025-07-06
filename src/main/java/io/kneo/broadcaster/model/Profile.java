package io.kneo.broadcaster.model;

import io.kneo.broadcaster.model.cnst.AnnouncementFrequency;
import io.kneo.core.model.SimpleReferenceEntity;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Setter
@Getter
@NoArgsConstructor
public class Profile extends SimpleReferenceEntity {
    private String name;
    private String description;
    private AnnouncementFrequency announcementFrequency;
    private boolean explicitContent;
}