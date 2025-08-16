package io.kneo.broadcaster.model;

import lombok.Getter;

import java.io.InputStream;

@Getter
public class FileData {
    private final byte[] data;
    private final InputStream inputStream;
    private final String mimeType;
    private final long contentLength;

    // Constructor for byte[] data (existing usage)
    public FileData(byte[] data, String mimeType) {
        this.data = data;
        this.inputStream = null;
        this.mimeType = mimeType;
        this.contentLength = data != null ? data.length : 0;
    }

    // Constructor for InputStream data (cloud storage)
    public FileData(InputStream inputStream, String mimeType, long contentLength) {
        this.data = null;
        this.inputStream = inputStream;
        this.mimeType = mimeType;
        this.contentLength = contentLength;
    }

    public boolean hasInputStream() {
        return inputStream != null;
    }

    public boolean hasByteArray() {
        return data != null;
    }
}
