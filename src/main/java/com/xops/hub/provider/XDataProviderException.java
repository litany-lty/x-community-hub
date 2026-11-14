package com.xops.hub.provider;

public class XDataProviderException extends RuntimeException {

    private final int statusCode;

    public XDataProviderException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public XDataProviderException(int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
