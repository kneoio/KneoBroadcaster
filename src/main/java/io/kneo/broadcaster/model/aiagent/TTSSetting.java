package io.kneo.broadcaster.model.aiagent;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class TTSSetting {
    private Voice dj;
    private Voice copilot;
    private Voice newsReporter;
    private Voice weatherReporter;
}