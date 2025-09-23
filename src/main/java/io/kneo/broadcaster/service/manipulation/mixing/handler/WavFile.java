package io.kneo.broadcaster.service.manipulation.mixing.handler;

import java.io.File;

public class WavFile {
    File file;
    double durationSeconds;

    WavFile(File file, double durationSeconds) {
        this.file = file;
        this.durationSeconds = durationSeconds;
    }
}
