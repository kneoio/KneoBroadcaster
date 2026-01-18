package io.kneo.broadcaster.model;

import io.kneo.broadcaster.model.cnst.SceneTimingMode;
import io.kneo.core.model.SecureDataEntity;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

@Setter
@Getter
@NoArgsConstructor
public class Scene extends SecureDataEntity<UUID> {
    private UUID scriptId;
    private String title;
    private String scriptTitle;
    private SceneTimingMode timingMode;
    private List<LivePrompt> prompts;
    private PlaylistRequest playlistRequest;
    private LocalTime startTime;
    private int durationSeconds;
    private int seqNum;
    private Integer archived;
    private boolean oneTimeRun;
    private double talkativity = 0.5;
    private List<Integer> weekdays;
    private List<UUID> soundFragmentIds;
}
