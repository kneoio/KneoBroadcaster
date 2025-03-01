package io.kneo.broadcaster.repository.exceptions;

public class DigitalOceanException extends RuntimeException {

    public DigitalOceanException(String msg) {
        super(msg);
    }

    public DigitalOceanException(Throwable failure) {
        super(failure);
    }

    public DigitalOceanException(String s, Exception e) {
        super(s, e);
    }

    public String getDeveloperMessage() {
        return getMessage();
    }
}
