package com.liang.gateway.orchestration.internal.web;

import com.liang.gateway.access.AccessPrincipal;
import com.liang.gateway.orchestration.internal.application.ChatCompletionService;
import com.liang.gateway.orchestration.internal.application.ChatCompletionService.ChatModelList;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
public class ChatCompletionsController {

    private final ChatCompletionService chatCompletionService;

    public ChatCompletionsController(ChatCompletionService chatCompletionService) {
        this.chatCompletionService = chatCompletionService;
    }

    @PostMapping(path = "/v1/chat/completions")
    public Mono<Void> completions(ServerWebExchange exchange) {
        Mono<byte[]> body = DataBufferUtils.join(exchange.getRequest().getBody())
                .map(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    DataBufferUtils.release(buffer);
                    return bytes;
                })
                .defaultIfEmpty(new byte[0]);
        return Mono.zip(currentTokenCode(), body)
                .flatMap(tuple -> chatCompletionService.complete(tuple.getT1(), tuple.getT2(), exchange));
    }

    @GetMapping(path = "/v1/models", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ChatModelList> models() {
        return currentTokenCode().flatMap(chatCompletionService::listModels);
    }

    private static Mono<String> currentTokenCode() {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .mapNotNull(Authentication::getPrincipal)
                .filter(AccessPrincipal.class::isInstance)
                .cast(AccessPrincipal.class)
                .map(AccessPrincipal::tokenCode)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized")));
    }
}
