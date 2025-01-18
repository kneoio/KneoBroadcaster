package io.kneo.broadcaster.model;

//TODO should move to core
public class FileData {
    private final byte[] data;
    private final String mimeType;

    public FileData(byte[] data, String mimeType) {
        this.data = data;
        this.mimeType = mimeType;
    }

    public byte[] getData() {
        return data;
    }

    public String getMimeType() {
        return mimeType;
    }
}
