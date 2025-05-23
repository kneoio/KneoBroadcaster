package io.kneo.broadcaster.model;

import lombok.Getter;

@Getter
public class FileData {
    private final byte[] data;
    private final String mimeType;

    public FileData(byte[] data, String mimeType) {
        this.data = data;
        this.mimeType = mimeType;
    }

}
