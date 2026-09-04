package com.liang.gateway.core.internal.web;

import com.liang.gateway.core.ProxyFailureException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponse;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

@Component
@Order(-2)
public class GatewayExceptionHandler implements WebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayExceptionHandler.class);

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (ex instanceof CancellationException || isCancellation(ex)) {
            return Mono.empty();
        }
        if (exchange.getResponse().isCommitted()) {
            return Mono.empty();
        }
        ProxyFailureException proxyFailure = findProxyFailure(ex);
        if (proxyFailure != null) {
            HttpStatus status = proxyFailure.status();
            String message = status == HttpStatus.GATEWAY_TIMEOUT ? "Gateway timeout" : "Bad gateway";
            return writeJson(exchange, status, "bad_gateway", message);
        }
        HttpStatusCode explicitStatus = findExplicitStatus(ex);
        if (explicitStatus != null) {
            return writeJson(exchange, explicitStatus, typeFor(explicitStatus), messageFor(explicitStatus));
        }
        log.error("Unhandled gateway error", ex);
        return writeJson(exchange, HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "Internal error");
    }

    private static boolean isCancellation(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof CancellationException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static ProxyFailureException findProxyFailure(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof ProxyFailureException proxyFailure) {
                return proxyFailure;
            }
            current = current.getCause();
        }
        return null;
    }

    private static HttpStatusCode findExplicitStatus(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof ResponseStatusException statusException) {
                return statusException.getStatusCode();
            }
            if (current instanceof ErrorResponse errorResponse) {
                return errorResponse.getStatusCode();
            }
            current = current.getCause();
        }
        return null;
    }

    private static String typeFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 404 -> "not_found";
            case 405 -> "method_not_allowed";
            default -> "request_error";
        };
    }

    private static String messageFor(HttpStatusCode status) {
        if (status instanceof HttpStatus httpStatus) {
            return httpStatus.getReasonPhrase();
        }
        return HttpStatus.resolve(status.value()) != null
                ? HttpStatus.resolve(status.value()).getReasonPhrase()
                : "Request error";
    }

    private static Mono<Void> writeJson(
            ServerWebExchange exchange, HttpStatusCode status, String type, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String json = "{\"error\":{\"message\":\"" + message + "\",\"type\":\"" + type + "\"}}";
        DataBuffer buffer = response.bufferFactory().wrap(json.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }
}
