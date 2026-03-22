package io.kneo.broadcaster.model;

import com.semantyca.core.model.SimpleReferenceEntity;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Setter
@Getter
@NoArgsConstructor
@Deprecated
public class Label extends SimpleReferenceEntity {
    private String identifier;
    private String slugName;
    private String color;
}
