package com.liang.gateway.orchestration.internal.web;

import com.liang.gateway.orchestration.internal.application.McpGatewayService;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
public class McpController {

    private final McpGatewayService mcpGatewayService;

    public McpController(McpGatewayService mcpGatewayService) {
        this.mcpGatewayService = mcpGatewayService;
    }

    @PostMapping(path = "/mcp/{path}")
    public Mono<ResponseEntity<byte[]>> handle(@PathVariable String path, ServerWebExchange exchange) {
        return DataBufferUtils.join(exchange.getRequest().getBody())
                .map(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    DataBufferUtils.release(buffer);
                    return bytes;
                })
                .defaultIfEmpty(new byte[0])
                .flatMap(body -> mcpGatewayService.handle(path, exchange.getRequest().getHeaders(), body));
    }
}
