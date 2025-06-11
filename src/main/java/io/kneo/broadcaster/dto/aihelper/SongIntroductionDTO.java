package io.kneo.broadcaster.dto.aihelper;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SongIntroductionDTO extends HistoryRecordDTO {
    private String title;
    private String artist;
    private String content;
}