package io.kneo.broadcaster.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class SoundFragment {
    private int id;
    private SourceType source;
    private int status;
    private String fileUri;
    private String localPath;
    private FragmentType type;
    private String name;
    private String author;
    private String createdAt;
    private String genre;
    private String album;
    private byte[] file;
}
