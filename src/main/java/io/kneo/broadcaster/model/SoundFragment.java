package io.kneo.broadcaster.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Setter
@Getter
@NoArgsConstructor
@SuperBuilder
public class SoundFragment {
    @JsonProperty("source")
    private String source;

    @JsonProperty("file_uri")
    private String fileUri;

    @JsonProperty("local_path")
    private String localPath;

    @JsonProperty("type")
    private String type;

    private String name;

    @JsonProperty("author")
    private String author;

    @JsonProperty("created_at")
    private String createdAt;

    @JsonProperty("genre")
    private String genre;

    @JsonProperty("album")
    private String album;
}
