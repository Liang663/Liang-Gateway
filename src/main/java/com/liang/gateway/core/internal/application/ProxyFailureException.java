package com.liang.gateway.core.internal.application;

import org.springframework.http.HttpStatus;

public final class ProxyFailureException extends RuntimeException {

    private final HttpStatus status;

    private ProxyFailureException(HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public static ProxyFailureException badGateway(Throwable cause) {
        return new ProxyFailureException(HttpStatus.BAD_GATEWAY, "Bad gateway", cause);
    }

    public static ProxyFailureException timeout(Throwable cause) {
        return new ProxyFailureException(HttpStatus.GATEWAY_TIMEOUT, "Gateway timeout", cause);
    }

    public HttpStatus status() {
        return status;
    }
}
