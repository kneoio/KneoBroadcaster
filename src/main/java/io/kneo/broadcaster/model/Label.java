package io.kneo.broadcaster.model;

import io.kneo.core.model.SimpleReferenceEntity;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Setter
@Getter
@NoArgsConstructor
public class Label extends SimpleReferenceEntity {
    private String identifier;
    private String slugName;
    private String color;
    private Integer archived = 0;
}
