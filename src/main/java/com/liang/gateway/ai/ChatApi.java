package com.liang.gateway.ai;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import reactor.core.publisher.Mono;

public interface ChatApi {

    Mono<List<ModelView>> listModels();

    Mono<ChatUpstream> prepare(String model, byte[] requestBody);

    Mono<Optional<ChatUsage>> readUsage(String model, byte[] jsonBody);

    Optional<TokenCounts> readSseUsageFrame(byte[] sseFrame);

    Mono<ChatUsage> priceUsage(String model, TokenCounts tokens);

    boolean isFirstContentFrame(byte[] sseFrame);

    Mono<Void> recordCallLog(
            String llmApikeyCode,
            String model,
            boolean success,
            String message,
            Integer firstTokenMs,
            int totalDurationMs);

    Mono<CallLogStats> stats(String llmApikeyCode, Instant from, Instant to, String model);
}
