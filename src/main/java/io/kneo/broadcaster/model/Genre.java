package io.kneo.broadcaster.model;

import com.semantyca.core.model.SimpleReferenceEntity;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Setter
@Getter
@NoArgsConstructor
public class Genre extends SimpleReferenceEntity {
    private String slugName;
    private String label;
}