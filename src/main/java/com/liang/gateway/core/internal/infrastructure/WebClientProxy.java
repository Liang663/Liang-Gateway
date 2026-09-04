package com.liang.gateway.core.internal.infrastructure;

import com.liang.gateway.core.GatewayExchange;
import com.liang.gateway.core.ProxyFailureException;
import com.liang.gateway.core.ProxyResponse;
import com.liang.gateway.core.Upstream;
import io.netty.handler.timeout.ReadTimeoutException;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.Disposable;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClientRequest;

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
        return forward(gatewayExchange.nativeExchange(), upstream, null, false);
    }

    public Mono<Void> forward(
            ServerWebExchange exchange, Upstream upstream, Consumer<byte[]> observe, boolean drainAfterCancel) {
        ServerHttpRequest inbound = exchange.getRequest();
        ServerHttpResponse response = exchange.getResponse();
        Mono<Void> work = open(inbound, upstream)
                .exchangeToMono(clientResponse ->
                        copyResponse(upstream, response, clientResponse, observe, drainAfterCancel))
                .onErrorMap(WebClientProxy::mapError);
        if (drainAfterCancel) {
            return ignoreDownstreamCancel(work);
        }
        return work;
    }

    public Mono<ProxyResponse> exchange(Upstream upstream) {
        return open(null, upstream)
                .exchangeToMono(clientResponse -> {
                    MediaType contentType = clientResponse.headers().contentType().orElse(null);
                    return clientResponse
                            .bodyToMono(byte[].class)
                            .defaultIfEmpty(new byte[0])
                            .map(body -> new ProxyResponse(clientResponse.statusCode(), body, contentType));
                })
                .onErrorMap(WebClientProxy::mapError);
    }

    private WebClient.RequestHeadersSpec<?> open(ServerHttpRequest inbound, Upstream upstream) {
        HttpMethod method = resolveMethod(inbound, upstream);
        WebClient.RequestBodySpec spec = this.webClient.method(method).uri(upstream.url());
        spec.headers(headers -> copyRequestHeaders(
                inbound == null ? new HttpHeaders() : inbound.getHeaders(), upstream, headers));
        if (upstream.timeout() != null) {
            spec.httpRequest(httpRequest -> {
                Object nativeRequest = httpRequest.getNativeRequest();
                if (nativeRequest instanceof HttpClientRequest nettyRequest) {
                    nettyRequest.responseTimeout(upstream.timeout());
                }
            });
        }
        if (upstream.body() != null) {
            return spec.bodyValue(upstream.body());
        }
        if (inbound != null) {
            return spec.body(BodyInserters.fromDataBuffers(inbound.getBody()));
        }
        return spec.body(BodyInserters.empty());
    }

    private static HttpMethod resolveMethod(ServerHttpRequest inbound, Upstream upstream) {
        if (upstream.method() != null) {
            return HttpMethod.valueOf(upstream.method().toUpperCase(Locale.ROOT));
        }
        if (inbound != null && inbound.getMethod() != null) {
            return inbound.getMethod();
        }
        return HttpMethod.GET;
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
            Upstream upstream,
            ServerHttpResponse response,
            ClientResponse clientResponse,
            Consumer<byte[]> observe,
            boolean drainAfterCancel) {
        response.setStatusCode(clientResponse.statusCode());
        HttpHeaders outbound = response.getHeaders();
        clientResponse.headers().asHttpHeaders().forEach((name, values) -> {
            if (isForwardedResponseHeader(name)) {
                outbound.put(name, List.copyOf(values));
            }
        });
        if (!upstream.stream()) {
            return clientResponse
                    .bodyToMono(byte[].class)
                    .defaultIfEmpty(new byte[0])
                    .flatMap(bytes -> {
                        if (observe != null) {
                            observe.accept(bytes);
                        }
                        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)))
                                .onErrorResume(ex -> drainAfterCancel && isClientDisconnect(ex)
                                        ? Mono.empty()
                                        : Mono.error(ex));
                    });
        }
        if (observe == null && !drainAfterCancel) {
            return response.writeAndFlushWith(clientResponse.bodyToFlux(DataBuffer.class).map(Mono::just));
        }
        Flux<byte[]> frames = SseEventFramer.frame(clientResponse.bodyToFlux(DataBuffer.class))
                .doOnNext(frame -> {
                    if (observe != null) {
                        observe.accept(frame);
                    }
                });
        return writeFrames(response, frames, drainAfterCancel);
    }

    private static Mono<Void> writeFrames(ServerHttpResponse response, Flux<byte[]> frames, boolean drainAfterCancel) {
        if (!drainAfterCancel) {
            return response.writeAndFlushWith(
                    frames.map(frame -> Mono.just(response.bufferFactory().wrap(frame))));
        }
        return frames.publish(shared -> {
            Mono<Void> write = response.writeAndFlushWith(
                            shared.map(frame -> Mono.just(response.bufferFactory().wrap(frame))))
                    .onErrorResume(ex -> isClientDisconnect(ex) ? Mono.empty() : Mono.error(ex));
            Mono<Void> drain = shared.then();
            return Mono.when(write, drain);
        }).then();
    }

    private static boolean isForwardedResponseHeader(String name) {
        return name != null && !HOP_BY_HOP_RESPONSE_HEADERS.contains(name.toLowerCase(Locale.ROOT));
    }

    private static Mono<Void> ignoreDownstreamCancel(Mono<Void> work) {
        return Mono.create(sink -> {
            AtomicBoolean cancelled = new AtomicBoolean(false);
            Disposable disposable = work.subscribe(
                    unused -> {},
                    throwable -> {
                        if (cancelled.compareAndSet(false, true)) {
                            sink.error(throwable);
                        }
                    },
                    () -> {
                        if (cancelled.compareAndSet(false, true)) {
                            sink.success();
                        }
                    });
            sink.onCancel(() -> cancelled.set(true));
            sink.onDispose(() -> {
                if (!cancelled.get()) {
                    disposable.dispose();
                }
            });
        });
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

    private static boolean isClientDisconnect(Throwable ex) {
        if (isCancellation(ex)) {
            return true;
        }
        Throwable current = ex;
        while (current != null) {
            String name = current.getClass().getName();
            if (current instanceof IOException) {
                String message = current.getMessage();
                if (message != null
                        && (message.contains("Broken pipe")
                                || message.contains("Connection reset")
                                || message.contains("Aborted")
                                || message.contains("closed"))) {
                    return true;
                }
            }
            if (name.contains("AbortedException") || name.contains("PrematureClose")) {
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
