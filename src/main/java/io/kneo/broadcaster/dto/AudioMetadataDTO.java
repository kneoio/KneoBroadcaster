package io.kneo.broadcaster.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.Duration;

@Setter
@Getter
public class AudioMetadataDTO {
    private String title;
    private String artist;
    private String album;
    private String albumArtist;
    private String genre;
    private String year;
    private String track;
    private String composer;
    private String comment;
    private String publisher;
    private String copyright;
    private String language;

    private Integer bitRate;
    private Integer sampleRate;
    private String channels;
    private String format;
    private String encodingType;
    private Boolean lossless;
    private Integer durationSeconds;
    private Duration duration;

    private Long fileSize;
    private String fileName;

}