package io.kneo.broadcaster.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

@Setter
@Getter
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ScriptExportDTO {
    private String name;
    private String description;
    private List<UUID> labels;
    private List<SceneExportDTO> scenes;
    private boolean extended;

    @Setter
    @Getter
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SceneExportDTO {
        private String title;
        private LocalTime startTime;
        private boolean oneTimeRun;
        private double talkativity;
        @JsonProperty("podcastProbability")
        private double podcastMode;
        private List<Integer> weekdays;
        private List<ScenePromptExportDTO> actions;
    }

    @Setter
    @Getter
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ScenePromptExportDTO {
        private UUID id;
        private String title;
        private boolean active;
        private BigDecimal weight;
        private String prompt;
        private String languageTag;
        private PromptDraftDTO draft;
    }

    @Setter
    @Getter
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PromptDraftDTO {
        private UUID id;
        private String content;
        private String languageTag;
    }
}

