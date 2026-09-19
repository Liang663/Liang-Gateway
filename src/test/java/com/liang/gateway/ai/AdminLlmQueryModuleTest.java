package com.liang.gateway.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.liang.gateway.ai.internal.infrastructure.persistence.LlmApikeyConfigEntity;
import com.liang.gateway.ai.internal.infrastructure.persistence.LlmApikeyConfigRepository;
import com.liang.gateway.ai.internal.infrastructure.persistence.LlmCallLogEntity;
import com.liang.gateway.ai.internal.infrastructure.persistence.LlmCallLogRepository;
import com.liang.gateway.support.MutableClock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.test.StepVerifier;

@ApplicationModuleTest(webEnvironment = WebEnvironment.NONE)
@Import(MutableClock.class)
class AdminLlmQueryModuleTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2098, 1, 5, 16, 0);
    @Autowired AdminLlmQueryApi api;
    @Autowired MutableClock clock;
    @Autowired LlmApikeyConfigRepository keys;
    @Autowired LlmCallLogRepository logs;
    @Autowired DatabaseClient database;
    private String key;

    @BeforeEach
    void seedKey() {
        clock.setInstant(Instant.parse("2098-01-05T08:00:00Z"));
        key = "query-" + UUID.randomUUID();
        keys.save(LlmApikeyConfigEntity.create(key, key, "mock", "https://example.invalid", "test-secret",
                "test-", true, null, NOW)).block();
    }

    @AfterEach
    void removeOwnRows() {
        database.sql("DELETE FROM llm_call_log WHERE llm_apikey_code = :key")
                .bind("key", key).fetch().rowsUpdated().block();
        database.sql("DELETE FROM llm_apikey_config WHERE code = :key")
                .bind("key", key).fetch().rowsUpdated().block();
        clock.reset();
    }

    @Test
    @DisplayName("数据库完成聚合；窗口左闭右开、上海时区、空小时和无TTFT均保持正确语义")
    void aggregatesWithinHalfOpenShanghaiWindow() {
        insert("a", true, 100, NOW.minusHours(24));
        insert("b", false, null, NOW.minusHours(1));
        insert("a", true, 300, NOW.minusMinutes(30));
        insert("excluded", true, 900, NOW.minusHours(24).minusNanos(1_000_000));
        insert("excluded", true, 900, NOW);
        StepVerifier.create(api.stats("24h")).assertNext(stats -> {
            assertThat(stats.total()).isEqualTo(3);
            assertThat(stats.successCount()).isEqualTo(2);
            assertThat(stats.successRate()).isEqualTo(2.0 / 3);
            assertThat(stats.averageFirstTokenMs()).isEqualTo(200.0);
            assertThat(stats.trend()).hasSize(24);
            assertThat(stats.trend().stream().mapToLong(AdminLlmQueryApi.TrendPoint::count).sum()).isEqualTo(3);
            assertThat(stats.trend().getFirst().time()).isEqualTo(NOW.minusHours(24));
            assertThat(stats.models()).containsExactly(
                    new AdminLlmQueryApi.ModelCount("a", 2), new AdminLlmQueryApi.ModelCount("b", 1));
        }).verifyComplete();
        StepVerifier.create(api.stats("7d")).assertNext(stats -> assertThat(stats.total()).isEqualTo(4))
                .verifyComplete();
    }

    @Test
    @DisplayName("没有数据时返回零汇总、空模型分布及null TTFT")
    void emptyStatsAreWellDefined() {
        StepVerifier.create(api.stats("24h")).assertNext(stats -> {
            assertThat(stats.total()).isZero();
            assertThat(stats.successRate()).isZero();
            assertThat(stats.averageFirstTokenMs()).isNull();
            assertThat(stats.models()).isEmpty();
            assertThat(stats.trend()).allMatch(bucket -> bucket.count() == 0);
        }).verifyComplete();
        insert("no-ttft", false, null, NOW.minusHours(1));
        StepVerifier.create(api.stats("24h"))
                .assertNext(stats -> assertThat(stats.averageFirstTokenMs()).isNull()).verifyComplete();
    }

    @Test
    @DisplayName("日志在数据库过滤分页，同时间按id倒序，额度上限100")
    void filtersAndPaginatesWithStableOrdering() {
        String first = insert("chosen", true, 100, NOW.minusHours(1));
        String second = insert("chosen", true, 200, NOW.minusHours(1));
        insert("chosen", false, null, NOW.minusMinutes(30));
        insert("other", true, 1, NOW.minusMinutes(20));
        var query = new AdminLlmQueryApi.LogQuery(1, 1, null, null, "chosen", key, true);
        StepVerifier.create(api.callLogs(query)).assertNext(page -> {
            assertThat(page.total()).isEqualTo(2);
            assertThat(page.items()).hasSize(1);
            assertThat(page.items().getFirst().code()).isEqualTo(second);
            assertThat(page.items().getFirst().llmApikeyCode()).isEqualTo(key);
        }).verifyComplete();
        StepVerifier.create(api.callLogs(new AdminLlmQueryApi.LogQuery(2, 1, null, null, "chosen", key, true)))
                .assertNext(page -> assertThat(page.items().getFirst().code()).isEqualTo(first)).verifyComplete();
        StepVerifier.create(api.callLogs(new AdminLlmQueryApi.LogQuery(3, 1, null, null, "chosen", key, true)))
                .assertNext(page -> { assertThat(page.items()).isEmpty(); assertThat(page.total()).isEqualTo(2); })
                .verifyComplete();
        StepVerifier.create(api.callLogs(new AdminLlmQueryApi.LogQuery(1, 500, null, null, null, key, false)))
                .assertNext(page -> { assertThat(page.pageSize()).isEqualTo(100); assertThat(page.total()).isEqualTo(1); })
                .verifyComplete();
    }

    @Test
    @DisplayName("拒绝错误窗口和分页参数")
    void rejectsInvalidQueries() {
        StepVerifier.create(api.stats("month")).expectError(AiBadRequestException.class).verify();
        StepVerifier.create(api.callLogs(new AdminLlmQueryApi.LogQuery(0, 20, null, null, null, null, null)))
                .expectError(AiBadRequestException.class).verify();
        var time = OffsetDateTime.parse("2098-01-05T16:00:00+08:00");
        StepVerifier.create(api.callLogs(new AdminLlmQueryApi.LogQuery(1, 20, time, time, null, null, null)))
                .expectError(AiBadRequestException.class).verify();
    }

    private String insert(String model, boolean success, Integer ttft, LocalDateTime at) {
        String code = UUID.randomUUID().toString();
        logs.save(LlmCallLogEntity.create(code, key, model, success, "test", ttft, 300, at)).block();
        return code;
    }
}
