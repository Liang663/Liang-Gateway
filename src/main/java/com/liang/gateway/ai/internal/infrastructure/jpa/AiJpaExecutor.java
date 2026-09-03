package com.liang.gateway.ai.internal.infrastructure.jpa;

import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Component
public class AiJpaExecutor {

    public <T> Mono<T> call(Supplier<T> supplier) {
        return Mono.fromCallable(supplier::get).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Void> run(Runnable runnable) {
        return Mono.fromRunnable(runnable).subscribeOn(Schedulers.boundedElastic()).then();
    }
}
