package com.liang.gateway.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.liang.gateway.ai.internal.application.AdminLlmQueryService;
import com.liang.gateway.ai.internal.application.LlmAdminQueryStore;
import com.liang.gateway.ai.internal.infrastructure.AiClock;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AdminLlmQueryServiceTest {
    private final LlmAdminQueryStore store = mock(LlmAdminQueryStore.class);
    private final AdminLlmQueryApi api = new AdminLlmQueryService(store,
            new AiClock(Clock.fixed(Instant.parse("2026-09-18T08:30:00Z"), ZoneOffset.UTC)));

    @Test
    @DisplayName("默认窗口使用上海时区，小时补零不改变数据库汇总")
    void fillsMissingHoursFromDatabaseAggregates() {
        when(store.summary(any(), any())).thenReturn(Mono.just(new LlmAdminQueryStore.Summary(2, 1, null)));
        when(store.trend(any(), any())).thenReturn(Flux.just(
                new LlmAdminQueryStore.Bucket(LocalDateTime.parse("2026-09-18T16:00:00"), 2)));
        when(store.models(any(), any())).thenReturn(Flux.just(new LlmAdminQueryStore.ModelCount("m", 2)));
        StepVerifier.create(api.stats("24h")).assertNext(stats -> {
            assertThat(stats.total()).isEqualTo(2);
            assertThat(stats.successRate()).isEqualTo(.5);
            assertThat(stats.averageFirstTokenMs()).isNull();
            assertThat(stats.trend()).hasSize(25);
            assertThat(stats.trend().getFirst().count()).isZero();
            assertThat(stats.trend().getLast().count()).isEqualTo(2);
        }).verifyComplete();
        verify(store).summary(LocalDateTime.parse("2026-09-17T16:30:00"),
                LocalDateTime.parse("2026-09-18T16:30:00"));
    }

    @Test
    @DisplayName("拒绝非法参数且不会触达数据库")
    void validatesBeforeQuerying() {
        StepVerifier.create(api.stats("year")).expectError(AiBadRequestException.class).verify();
        StepVerifier.create(api.callLogs(new AdminLlmQueryApi.LogQuery(-1, 20, null, null, null, null, null)))
                .expectError(AiBadRequestException.class).verify();
        var at = OffsetDateTime.parse("2026-09-18T00:00:00Z");
        StepVerifier.create(api.callLogs(new AdminLlmQueryApi.LogQuery(1, 20, at, at, null, null, null)))
                .expectError(AiBadRequestException.class).verify();
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("分页上限和偏移在应用层确定，时间偏移转换为上海时间")
    void boundsPaginationAndConvertsOffsets() {
        when(store.count(any())).thenReturn(Mono.just(0L));
        when(store.logs(any(), eq(200L), eq(100))).thenReturn(Flux.empty());
        StepVerifier.create(api.callLogs(new AdminLlmQueryApi.LogQuery(3, 999,
                OffsetDateTime.parse("2026-09-17T00:00:00Z"),
                OffsetDateTime.parse("2026-09-18T00:00:00Z"), " model ", " ", false)))
                .assertNext(page -> { assertThat(page.pageSize()).isEqualTo(100); assertThat(page.items()).isEmpty(); })
                .verifyComplete();
        var filter = ArgumentCaptor.forClass(LlmAdminQueryStore.Filter.class);
        verify(store).count(filter.capture());
        assertThat(filter.getValue().from()).isEqualTo(LocalDateTime.parse("2026-09-17T08:00:00"));
        assertThat(filter.getValue().to()).isEqualTo(LocalDateTime.parse("2026-09-18T08:00:00"));
        assertThat(filter.getValue().model()).isEqualTo("model");
        assertThat(filter.getValue().apikeyCode()).isNull();
        assertThat(filter.getValue().success()).isFalse();
    }
}
