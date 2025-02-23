package io.kneo.broadcaster.service.exceptions;

public class PlaylistException extends RuntimeException {

    public PlaylistException(String msg) {
        super(msg);
    }

    public PlaylistException(Throwable failure) {
        super(failure);
    }

    public String getDeveloperMessage() {
        return getMessage();
    }
}
