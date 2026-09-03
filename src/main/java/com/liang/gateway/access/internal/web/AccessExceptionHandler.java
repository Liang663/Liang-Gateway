package com.liang.gateway.access.internal.web;

import com.liang.gateway.access.QuotaExceededException;
import com.liang.gateway.access.QuotaStoreUnavailableException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

@Component
@Order(-3)
public class AccessExceptionHandler implements WebExceptionHandler {

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        QuotaExceededException quotaExceeded = find(ex, QuotaExceededException.class);
        if (quotaExceeded != null) {
            return ErrorEnvelope.write(exchange, HttpStatus.TOO_MANY_REQUESTS, "quota_exceeded", quotaExceeded.getMessage());
        }
        QuotaStoreUnavailableException unavailable = find(ex, QuotaStoreUnavailableException.class);
        if (unavailable != null) {
            return ErrorEnvelope.write(
                    exchange, HttpStatus.SERVICE_UNAVAILABLE, "service_unavailable", "Quota store unavailable");
        }
        return Mono.error(ex);
    }

    private static <T extends Throwable> T find(Throwable ex, Class<T> type) {
        Throwable current = ex;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }
}
