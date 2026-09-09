package com.liang.gateway.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.liang.gateway.ai.internal.application.ApikeySnapshot;
import com.liang.gateway.ai.internal.application.LlmApikeyAdminService;
import com.liang.gateway.ai.internal.infrastructure.persistence.LlmCallLogRepository;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.test.StepVerifier;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ChatApiCallLogTest {

    private static final String SECRET = "sk-test-secret-value";

    @Autowired
    private ChatApi chatApi;

    @Autowired
    private LlmApikeyAdminService apikeyAdminService;

    @Autowired
    private LlmCallLogRepository callLogRepository;

    @Test
    @DisplayName("出站日志汇总与插入一致，message 不含 secret；第一帧前取消不插行")
    void callLogStatsMatchInsertsAndRedactSecret() {
        ApikeySnapshot key = apikeyAdminService
                .create("log-key", "deepseek", "https://api.deepseek.com", SECRET, true, null)
                .block();
        String model = "deepseek-chat";

        StepVerifier.create(chatApi.stats(key.code(), Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-12-31T00:00:00Z"), null))
                .assertNext(stats -> {
                    assertThat(stats.total()).isZero();
                    assertThat(stats.successCount()).isZero();
                    assertThat(stats.averageFirstTokenMs()).isNull();
                    assertThat(stats.failureRate()).isZero();
                })
                .verifyComplete();

        StepVerifier.create(chatApi.recordCallLog(
                        key.code(), model, true, "ok " + SECRET, 120, 800))
                .verifyComplete();
        StepVerifier.create(chatApi.recordCallLog(
                        key.code(), model, false, "upstream timeout", null, 50))
                .verifyComplete();

        StepVerifier.create(chatApi.stats(key.code(), Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-12-31T00:00:00Z"), model))
                .assertNext(stats -> {
                    assertThat(stats.total()).isEqualTo(2L);
                    assertThat(stats.successCount()).isEqualTo(1L);
                    assertThat(stats.averageFirstTokenMs()).isEqualTo(120.0d);
                    assertThat(stats.failureRate()).isEqualTo(0.5d);
                })
                .verifyComplete();

        var rows = callLogRepository.findByLlmApikeyCodeOrderByCreateTimeDesc(key.code()).collectList().block();
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.getMessage()).doesNotContain(SECRET);
            assertThat(row.getCreateTime()).isEqualTo(row.getUpdateTime());
        });
        assertThat(rows.stream().anyMatch(row -> "ok ***".equals(row.getMessage()))).isTrue();
    }
}
