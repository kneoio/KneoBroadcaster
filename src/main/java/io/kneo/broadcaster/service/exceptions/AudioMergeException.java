package io.kneo.broadcaster.service.exceptions;

public class AudioMergeException extends Exception {
    public AudioMergeException(String message, Throwable cause) {
        super(message, cause);
    }

    public AudioMergeException(String message) {
        super(message);
    }
}
