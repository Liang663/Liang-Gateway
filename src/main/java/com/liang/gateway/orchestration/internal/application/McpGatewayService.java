package com.liang.gateway.orchestration.internal.application;

import com.liang.gateway.ai.McpApi;
import com.liang.gateway.ai.McpUpstream;
import com.liang.gateway.core.ProxyApi;
import com.liang.gateway.core.ProxyFailureException;
import com.liang.gateway.core.Upstream;
import com.liang.gateway.orchestration.internal.infrastructure.McpGatewayProperties;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

@Service
@EnableConfigurationProperties(McpGatewayProperties.class)
public class McpGatewayService {

    private static final String HEADER_PROTOCOL_VERSION = "MCP-Protocol-Version";
    private static final String HEADER_METHOD = "Mcp-Method";
    private static final String HEADER_NAME = "Mcp-Name";

    private final McpApi mcpApi;
    private final ProxyApi proxyApi;
    private final McpGatewayProperties properties;
    private final JsonMapper objectMapper;

    public McpGatewayService(
            McpApi mcpApi, ProxyApi proxyApi, McpGatewayProperties properties, JsonMapper objectMapper) {
        this.mcpApi = mcpApi;
        this.proxyApi = proxyApi;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public Mono<ResponseEntity<byte[]>> handle(String path, HttpHeaders headers, byte[] rawBody) {
        ResponseEntity<byte[]> originDenied = rejectOrigin(headers);
        if (originDenied != null) {
            return Mono.just(originDenied);
        }
        if (!acceptsJsonAndSse(headers)) {
            return Mono.just(ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).build());
        }
        if (!isJsonContentType(headers.getContentType())) {
            return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST).build());
        }
        McpApi.Transport transport = new McpApi.Transport(
                headers.getFirst(HEADER_PROTOCOL_VERSION),
                headers.getFirst(HEADER_METHOD),
                headers.getFirst(HEADER_NAME));
        return mcpApi.handle(path, transport, rawBody).flatMap(this::toResponse);
    }

    private Mono<ResponseEntity<byte[]>> toResponse(McpApi.Outcome outcome) {
        return switch (outcome) {
            case McpApi.Outcome.Reply reply -> Mono.just(writeReply(reply));
            case McpApi.Outcome.NeedsOutbound outbound -> exchange(outbound);
        };
    }

    private Mono<ResponseEntity<byte[]>> exchange(McpApi.Outcome.NeedsOutbound outbound) {
        return proxyApi
                .exchange(toUpstream(outbound.upstream()))
                .flatMap(response -> mcpApi.completeCall(
                        outbound.id(),
                        response.status() == null ? null : response.status().value(),
                        new String(response.body(), StandardCharsets.UTF_8),
                        false))
                .onErrorResume(ex -> completeFailed(outbound.id(), ex))
                .map(this::writeReply);
    }

    private Mono<McpApi.Outcome.Reply> completeFailed(Object id, Throwable ex) {
        ProxyFailureException failure = findProxyFailure(ex);
        if (failure != null && failure.status() == HttpStatus.GATEWAY_TIMEOUT) {
            return mcpApi.completeCall(id, null, null, true);
        }
        if (failure != null) {
            return mcpApi.completeCall(id, failure.status().value(), failure.getMessage(), false);
        }
        return mcpApi.completeCall(id, null, null, false);
    }

    private ResponseEntity<byte[]> writeReply(McpApi.Outcome.Reply reply) {
        return ResponseEntity.status(reply.httpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsBytes(reply.jsonRpc()));
    }

    private ResponseEntity<byte[]> rejectOrigin(HttpHeaders headers) {
        String origin = headers.getOrigin();
        if (origin == null || origin.isBlank()) {
            return null;
        }
        List<String> allowed = properties.getAllowedOrigins();
        if (allowed != null && allowed.contains(origin)) {
            return null;
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    private static boolean acceptsJsonAndSse(HttpHeaders headers) {
        List<MediaType> accept = headers.getAccept();
        boolean json = false;
        boolean sse = false;
        for (MediaType mediaType : accept) {
            if (MediaType.APPLICATION_JSON.isCompatibleWith(mediaType)) {
                json = true;
            }
            if (MediaType.TEXT_EVENT_STREAM.isCompatibleWith(mediaType)) {
                sse = true;
            }
        }
        if (json && sse) {
            return true;
        }
        String raw = headers.getFirst(HttpHeaders.ACCEPT);
        if (raw == null) {
            return false;
        }
        String lower = raw.toLowerCase();
        return lower.contains("application/json") && lower.contains("text/event-stream");
    }

    private static boolean isJsonContentType(MediaType contentType) {
        return contentType != null && MediaType.APPLICATION_JSON.isCompatibleWith(contentType);
    }

    private static Upstream toUpstream(McpUpstream mcp) {
        Duration timeout = mcp.timeoutMs() > 0 ? Duration.ofMillis(mcp.timeoutMs()) : null;
        return new Upstream(
                URI.create(mcp.url()), mcp.headers(), false, mcp.httpMethod(), mcp.body(), timeout);
    }

    private static ProxyFailureException findProxyFailure(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof ProxyFailureException failure) {
                return failure;
            }
            current = current.getCause();
        }
        return null;
    }
}
