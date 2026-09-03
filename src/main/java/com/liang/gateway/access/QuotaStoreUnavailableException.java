package com.liang.gateway.access;

public class QuotaStoreUnavailableException extends RuntimeException {

    public QuotaStoreUnavailableException(String message) {
        super(message);
    }

    public QuotaStoreUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
