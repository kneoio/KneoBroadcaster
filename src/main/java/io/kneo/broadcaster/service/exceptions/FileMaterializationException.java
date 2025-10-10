package io.kneo.broadcaster.service.exceptions;

public class FileMaterializationException extends Exception {
    public FileMaterializationException(String message, Throwable cause) {
        super(message, cause);
    }

    public FileMaterializationException(String message) {
        super(message);
    }
}
