package io.kneo.broadcaster.model;

import io.kneo.core.model.SecureDataEntity;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Setter
@Getter
@NoArgsConstructor
public class Listener extends SecureDataEntity<UUID> {
    private Long userId;
    private Long author;
    private LocalDateTime regDate;
    private Long lastModUser;
    private LocalDateTime lastModDate;
    private String country;
    private Map<String, Object> locName;
    private List<String> nickName;
    private String slugName;
    private Integer archived;
}
