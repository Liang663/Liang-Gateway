package com.liang.gateway.core.internal.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class SseEventFramerTest {

    private final DefaultDataBufferFactory factory = new DefaultDataBufferFactory();

    @Test
    @DisplayName("半截 DataBuffer 会拼成完整 SSE 事件")
    void splitChunksAreReassembledIntoCompleteEvents() {
        DataBuffer first = wrap("data: {\"a\"");
        DataBuffer second = wrap(":1}\n\ndata: b\n\n");

        StepVerifier.create(SseEventFramer.frame(Flux.just(first, second)))
                .assertNext(frame -> assertThat(new String(frame, StandardCharsets.UTF_8))
                        .isEqualTo("data: {\"a\":1}\n\n"))
                .assertNext(frame -> assertThat(new String(frame, StandardCharsets.UTF_8)).isEqualTo("data: b\n\n"))
                .verifyComplete();
    }

    @Test
    @DisplayName("结束时把没有分隔符的残留当作最后一帧")
    void remainderIsEmittedOnComplete() {
        StepVerifier.create(SseEventFramer.frame(Flux.just(wrap("data: leftover"))))
                .assertNext(frame -> assertThat(new String(frame, StandardCharsets.UTF_8)).isEqualTo("data: leftover"))
                .verifyComplete();
    }

    private DataBuffer wrap(String text) {
        return factory.wrap(text.getBytes(StandardCharsets.UTF_8));
    }
}
