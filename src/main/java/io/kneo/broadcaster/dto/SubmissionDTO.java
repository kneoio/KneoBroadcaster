package io.kneo.broadcaster.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.List;
import java.util.UUID;

@Setter
@Getter
@SuperBuilder
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SubmissionDTO {
    @NotBlank
    private String confirmationCode;
    @NotBlank
    private String title;
    @NotBlank
    private String artist;
    @NotNull
    @NotEmpty
    private List<UUID> genres;
    private String album;
    private String email;
    private String description;
    private List<String> newlyUploaded;
    private List<UUID> representedInBrands;
    @NotBlank
    @JsonProperty("email")
    private String contributorEmail;
    private boolean isShareable;
    private String message;

    public String toString() {
        return String.format("%s|%s", title, artist);
    }
}