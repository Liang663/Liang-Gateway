package com.liang.gateway.core.internal.infrastructure;

import com.liang.gateway.core.GatewayExchange;
import com.liang.gateway.core.Upstream;
import com.liang.gateway.core.internal.application.ProxyFailureException;
import io.netty.handler.timeout.ReadTimeoutException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;

@Component
public class WebClientProxy {

    private static final Set<String> FORWARDED_REQUEST_HEADERS = Set.of(
            HttpHeaders.CONTENT_TYPE.toLowerCase(Locale.ROOT),
            HttpHeaders.ACCEPT.toLowerCase(Locale.ROOT),
            HttpHeaders.ACCEPT_LANGUAGE.toLowerCase(Locale.ROOT));

    private static final Set<String> HOP_BY_HOP_RESPONSE_HEADERS = Set.of(
            "connection",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailers",
            "transfer-encoding",
            "upgrade",
            "content-length");

    private final WebClient webClient;

    public WebClientProxy(@Qualifier("gatewayWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    public Mono<Void> forward(GatewayExchange gatewayExchange) {
        Upstream upstream = gatewayExchange.getUpstream().orElseThrow();
        ServerWebExchange exchange = gatewayExchange.nativeExchange();
        ServerHttpRequest request = exchange.getRequest();
        ServerHttpResponse response = exchange.getResponse();
        HttpMethod method = request.getMethod() == null ? HttpMethod.GET : request.getMethod();

        return this.webClient
                .method(method)
                .uri(upstream.url())
                .headers(headers -> copyRequestHeaders(request.getHeaders(), upstream, headers))
                .body(BodyInserters.fromDataBuffers(request.getBody()))
                .exchangeToMono(clientResponse -> copyResponse(upstream, response, clientResponse))
                .onErrorMap(WebClientProxy::mapError);
    }

    private static void copyRequestHeaders(HttpHeaders inbound, Upstream upstream, HttpHeaders outbound) {
        outbound.clear();
        inbound.forEach((name, values) -> {
            if (isForwardedRequestHeader(name)) {
                outbound.put(name, values);
            }
        });
        upstream.extraHeaders().forEach(outbound::set);
    }

    private static boolean isForwardedRequestHeader(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (HttpHeaders.AUTHORIZATION.equalsIgnoreCase(lower)) {
            return false;
        }
        return FORWARDED_REQUEST_HEADERS.contains(lower);
    }

    private static Mono<Void> copyResponse(
            Upstream upstream, ServerHttpResponse response, ClientResponse clientResponse) {
        response.setStatusCode(clientResponse.statusCode());
        HttpHeaders outbound = response.getHeaders();
        clientResponse.headers().asHttpHeaders().forEach((name, values) -> {
            if (isForwardedResponseHeader(name)) {
                outbound.put(name, List.copyOf(values));
            }
        });
        if (upstream.stream()) {
            return response.writeAndFlushWith(clientResponse.bodyToFlux(DataBuffer.class).map(Mono::just));
        }
        return clientResponse
                .bodyToMono(byte[].class)
                .defaultIfEmpty(new byte[0])
                .flatMap(bytes -> response.writeWith(Mono.just(response.bufferFactory().wrap(bytes))));
    }

    private static boolean isForwardedResponseHeader(String name) {
        return name != null && !HOP_BY_HOP_RESPONSE_HEADERS.contains(name.toLowerCase(Locale.ROOT));
    }

    private static Throwable mapError(Throwable ex) {
        if (isCancellation(ex)) {
            return ex;
        }
        if (ex instanceof ProxyFailureException) {
            return ex;
        }
        if (isTimeout(ex)) {
            return ProxyFailureException.timeout(ex);
        }
        return ProxyFailureException.badGateway(ex);
    }

    private static boolean isCancellation(Throwable ex) {
        if (Exceptions.isCancel(ex)) {
            return true;
        }
        Throwable current = ex;
        while (current != null) {
            if (current instanceof CancellationException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean isTimeout(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof TimeoutException
                    || current instanceof ReadTimeoutException
                    || current instanceof io.netty.handler.timeout.TimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
