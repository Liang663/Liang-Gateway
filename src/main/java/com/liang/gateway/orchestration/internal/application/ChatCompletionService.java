package com.liang.gateway.orchestration.internal.application;

import com.liang.gateway.access.AccessApi;
import com.liang.gateway.access.UsageMeta;
import com.liang.gateway.ai.AiBadRequestException;
import com.liang.gateway.ai.AiNotFoundException;
import com.liang.gateway.ai.ChatApi;
import com.liang.gateway.ai.ChatUpstream;
import com.liang.gateway.ai.ChatUsage;
import com.liang.gateway.ai.ModelView;
import com.liang.gateway.ai.TokenCounts;
import com.liang.gateway.core.ProxyApi;
import com.liang.gateway.core.ProxyFailureException;
import com.liang.gateway.core.ProxyResponse;
import com.liang.gateway.core.Upstream;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ChatCompletionService {

    private final AccessApi accessApi;
    private final ChatApi chatApi;
    private final ProxyApi proxyApi;
    private final JsonMapper objectMapper;

    public ChatCompletionService(AccessApi accessApi, ChatApi chatApi, ProxyApi proxyApi, JsonMapper objectMapper) {
        this.accessApi = accessApi;
        this.chatApi = chatApi;
        this.proxyApi = proxyApi;
        this.objectMapper = objectMapper;
    }

    public Mono<Void> complete(String tokenCode, byte[] rawBody, ServerWebExchange exchange) {
        ParsedRequest parsed;
        try {
            parsed = parse(rawBody);
        } catch (ResponseStatusException ex) {
            return Mono.error(ex);
        }
        String requestId = UUID.randomUUID().toString();
        byte[] inbound = rawBody == null ? new byte[0] : rawBody;
        return accessApi
                .assertModelAllowed(tokenCode, parsed.model())
                .then(accessApi.checkQuota(tokenCode))
                .then(chatApi.prepare(parsed.model(), inbound))
                .onErrorMap(ChatCompletionService::mapPrepareError)
                .flatMap(upstream -> dispatch(tokenCode, parsed.model(), requestId, upstream, exchange));
    }

    public Mono<ChatModelList> listModels(String tokenCode) {
        return Mono.zip(accessApi.listAllowedModels(tokenCode), chatApi.listModels())
                .map(tuple -> {
                    Set<String> allowed = new LinkedHashSet<>(tuple.getT1());
                    List<ChatModelList.Item> data = tuple.getT2().stream()
                            .map(ModelView::name)
                            .filter(allowed::contains)
                            .sorted()
                            .map(name -> new ChatModelList.Item(name, "model"))
                            .toList();
                    return new ChatModelList("list", data);
                });
    }

    private Mono<Void> dispatch(
            String tokenCode, String model, String requestId, ChatUpstream chat, ServerWebExchange exchange) {
        Upstream upstream = toUpstream(chat);
        long started = System.nanoTime();
        if (chat.stream()) {
            return completeStream(tokenCode, model, requestId, chat.apikeyCode(), upstream, exchange, started);
        }
        return completeNonStream(tokenCode, model, requestId, chat.apikeyCode(), upstream, exchange, started);
    }

    private Mono<Void> completeNonStream(
            String tokenCode,
            String model,
            String requestId,
            String apikeyCode,
            Upstream upstream,
            ServerWebExchange exchange,
            long started) {
        AtomicBoolean settled = new AtomicBoolean(false);
        return proxyApi
                .exchange(upstream)
                .flatMap(response -> settleNonStream(
                                tokenCode, model, requestId, apikeyCode, exchange, started, response)
                        .doOnTerminate(() -> settled.set(true)))
                .onErrorResume(ex -> {
                    if (settled.get()) {
                        return Mono.error(ex);
                    }
                    return chatApi.recordCallLog(
                                    apikeyCode, model, false, messageOf(ex), null, elapsedMs(started))
                            .then(Mono.error(ex));
                });
    }

    private Mono<Void> settleNonStream(
            String tokenCode,
            String model,
            String requestId,
            String apikeyCode,
            ServerWebExchange exchange,
            long started,
            ProxyResponse response) {
        int totalMs = elapsedMs(started);
        HttpStatusCode status = response.status();
        if (status == null || !status.is2xxSuccessful()) {
            String message = "upstream " + (status == null ? 0 : status.value());
            return chatApi.recordCallLog(apikeyCode, model, false, message, null, totalMs)
                    .then(writeResponse(exchange, status, response.contentType(), response.body()));
        }
        return chatApi.readUsage(model, response.body()).flatMap(usage -> {
            if (usage.isEmpty()) {
                return chatApi.recordCallLog(apikeyCode, model, false, "missing usage", null, totalMs)
                        .then(Mono.error(ProxyFailureException.badGateway(null)));
            }
            ChatUsage billed = usage.get();
            return accessApi
                    .recordUsage(
                            tokenCode,
                            billed.promptTokens(),
                            billed.completionTokens(),
                            billed.amountFen(),
                            model,
                            new UsageMeta(model, requestId))
                    .then(chatApi.recordCallLog(apikeyCode, model, true, null, null, totalMs))
                    .then(writeResponse(exchange, status, response.contentType(), response.body()));
        });
    }

    private Mono<Void> completeStream(
            String tokenCode,
            String model,
            String requestId,
            String apikeyCode,
            Upstream upstream,
            ServerWebExchange exchange,
            long started) {
        AtomicBoolean observedFrame = new AtomicBoolean(false);
        AtomicBoolean settled = new AtomicBoolean(false);
        AtomicReference<Integer> firstTokenMs = new AtomicReference<>();
        AtomicReference<TokenCounts> latestUsage = new AtomicReference<>();
        Mono<Void> pipeline = proxyApi
                .forward(exchange, upstream, frame -> {
                    observedFrame.set(true);
                    if (firstTokenMs.get() == null && chatApi.isFirstContentFrame(frame)) {
                        firstTokenMs.compareAndSet(null, elapsedMs(started));
                    }
                    chatApi.readSseUsageFrame(frame).ifPresent(latestUsage::set);
                }, true)
                .then(Mono.defer(() -> settleStream(
                                tokenCode,
                                model,
                                requestId,
                                apikeyCode,
                                started,
                                observedFrame.get(),
                                firstTokenMs.get(),
                                latestUsage.get())
                        .doOnTerminate(() -> settled.set(true))))
                .onErrorResume(ex -> {
                    if (settled.get()) {
                        return Mono.error(ex);
                    }
                    TokenCounts tokens = latestUsage.get();
                    if (tokens != null) {
                        return settleStream(
                                        tokenCode,
                                        model,
                                        requestId,
                                        apikeyCode,
                                        started,
                                        observedFrame.get(),
                                        firstTokenMs.get(),
                                        tokens)
                                .doOnTerminate(() -> settled.set(true));
                    }
                    return chatApi.recordCallLog(
                                    apikeyCode,
                                    model,
                                    false,
                                    messageOf(ex),
                                    firstTokenMs.get(),
                                    elapsedMs(started))
                            .doOnTerminate(() -> settled.set(true))
                            .then(Mono.error(ex));
                });
        return completeEvenIfCancelled(pipeline);
    }

    private Mono<Void> settleStream(
            String tokenCode,
            String model,
            String requestId,
            String apikeyCode,
            long started,
            boolean observedFrame,
            Integer firstTokenMs,
            TokenCounts tokens) {
        int totalMs = elapsedMs(started);
        if (tokens != null) {
            return chatApi.priceUsage(model, tokens)
                    .flatMap(usage -> accessApi
                            .recordUsage(
                                    tokenCode,
                                    usage.promptTokens(),
                                    usage.completionTokens(),
                                    usage.amountFen(),
                                    model,
                                    new UsageMeta(model, requestId))
                            .then(chatApi.recordCallLog(
                                    apikeyCode, model, true, null, firstTokenMs, totalMs)));
        }
        if (observedFrame) {
            return chatApi.recordCallLog(apikeyCode, model, false, "missing usage", firstTokenMs, totalMs);
        }
        return Mono.empty();
    }

    private ParsedRequest parse(byte[] rawBody) {
        if (rawBody == null || rawBody.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid JSON");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody);
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid JSON");
        }
        if (root == null || !root.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid JSON");
        }
        JsonNode modelNode = root.get("model");
        if (modelNode == null || modelNode.isNull() || !modelNode.isTextual() || modelNode.asText().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "model is required");
        }
        return new ParsedRequest(modelNode.asText());
    }

    private static Upstream toUpstream(ChatUpstream chat) {
        return new Upstream(URI.create(chat.url()), chat.extraHeaders(), chat.stream(), "POST", chat.body(), null);
    }

    private static Throwable mapPrepareError(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof AiNotFoundException) {
                return new ResponseStatusException(HttpStatus.NOT_FOUND, current.getMessage(), ex);
            }
            if (current instanceof AiBadRequestException) {
                return new ResponseStatusException(HttpStatus.BAD_REQUEST, current.getMessage(), ex);
            }
            current = current.getCause();
        }
        return ex;
    }

    private static Mono<Void> writeResponse(
            ServerWebExchange exchange, HttpStatusCode status, MediaType contentType, byte[] body) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.empty();
        }
        response.setStatusCode(status);
        if (contentType != null) {
            response.getHeaders().setContentType(contentType);
        }
        byte[] payload = body == null ? new byte[0] : body;
        return response.writeWith(Mono.just(response.bufferFactory().wrap(payload)));
    }

    private static int elapsedMs(long startedNanos) {
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        if (ms < 0) {
            return 0;
        }
        return ms > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) ms;
    }

    private static String messageOf(Throwable ex) {
        if (ex == null || ex.getMessage() == null || ex.getMessage().isBlank()) {
            return ex == null ? "upstream error" : ex.getClass().getSimpleName();
        }
        return ex.getMessage();
    }

    // Client disconnect cancels the controller Mono; billing still has to run after drain.
    private static Mono<Void> completeEvenIfCancelled(Mono<Void> work) {
        return Mono.create(sink -> {
            AtomicBoolean done = new AtomicBoolean(false);
            Disposable disposable = work.subscribe(
                    unused -> {},
                    throwable -> {
                        if (done.compareAndSet(false, true)) {
                            sink.error(throwable);
                        }
                    },
                    () -> {
                        if (done.compareAndSet(false, true)) {
                            sink.success();
                        }
                    });
            sink.onCancel(() -> done.set(true));
            sink.onDispose(() -> {
                if (!done.get()) {
                    disposable.dispose();
                }
            });
        });
    }

    private record ParsedRequest(String model) {}

    public record ChatModelList(String object, List<Item> data) {
        public record Item(String id, String object) {}
    }
}
