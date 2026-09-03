package com.liang.gateway.access;

import static org.assertj.core.api.Assertions.assertThat;

import com.liang.gateway.access.internal.application.TokenAdminService;
import com.liang.gateway.access.internal.application.TokenSnapshot;
import com.liang.gateway.access.internal.application.UserAdminService;
import com.liang.gateway.access.internal.infrastructure.AccessClock;
import com.liang.gateway.access.support.PlaceholderApiKeys;
import com.liang.gateway.support.MutableClock;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.test.StepVerifier;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AccessApiQuotaTest {

    @Autowired
    private AccessApi accessApi;

    @Autowired
    private UserAdminService userAdminService;

    @Autowired
    private TokenAdminService tokenAdminService;

    @Autowired
    private MutableClock accessClock;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ReactiveStringRedisTemplate redis;

    @AfterEach
    void resetClock() {
        accessClock.reset();
    }

    @Test
    @DisplayName("令牌一创建即可查出两档额度条，用量为 0")
    void createdTokenHasZeroQuotaBars() {
        Instant start = Instant.parse("2026-09-03T07:00:00Z");
        accessClock.setInstant(start);
        TokenSnapshot token = createToken(60, 100L, 1000L);

        StepVerifier.create(accessApi.getQuota(token.code()))
                .assertNext(view -> {
                    assertThat(view.fiveHour().used()).isZero();
                    assertThat(view.fiveHour().limit()).isEqualTo(100L);
                    assertThat(view.fiveHour().windowStart()).isEqualTo(start);
                    assertThat(view.fiveHour().windowEnd()).isEqualTo(start.plusSeconds(18_000));
                    assertThat(view.week().used()).isZero();
                    assertThat(view.week().limit()).isEqualTo(1000L);
                    assertThat(view.week().windowStart()).isEqualTo(start);
                    assertThat(view.week().windowEnd()).isEqualTo(start.plusSeconds(604_800));
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("7 点开窗用完后 14 点再来，start 接到 12 点而不是 14 点")
    void lazyRefreshAlignsToPreviousWindowEnd() {
        Instant seven = Instant.parse("2026-09-03T07:00:00Z");
        accessClock.setInstant(seven);
        TokenSnapshot token = createToken(60, 50L, 1000L);
        StepVerifier.create(accessApi.recordUsage(token.code(), 50, 0, UsageMeta.empty())).verifyComplete();

        accessClock.setInstant(Instant.parse("2026-09-03T14:00:00Z"));
        Instant expectedStart = Instant.parse("2026-09-03T12:00:00Z");
        StepVerifier.create(accessApi.getQuota(token.code()))
                .assertNext(view -> {
                    assertThat(view.fiveHour().windowStart()).isEqualTo(expectedStart);
                    assertThat(view.fiveHour().windowEnd()).isEqualTo(expectedStart.plusSeconds(18_000));
                    assertThat(view.fiveHour().used()).isZero();
                })
                .verifyComplete();
        StepVerifier.create(accessApi.checkQuota(token.code())).verifyComplete();
    }

    @Test
    @DisplayName("窗口未到期时持续记账不延长 start")
    void recordingDoesNotSlideWindow() {
        Instant start = Instant.parse("2026-09-03T07:00:00Z");
        accessClock.setInstant(start);
        TokenSnapshot token = createToken(60, 10_000L, 10_000L);
        StepVerifier.create(accessApi.recordUsage(token.code(), 10, 5, UsageMeta.empty())).verifyComplete();
        accessClock.setInstant(start.plusSeconds(3_600));
        StepVerifier.create(accessApi.recordUsage(token.code(), 1, 1, UsageMeta.empty())).verifyComplete();

        StepVerifier.create(accessApi.getQuota(token.code()))
                .assertNext(view -> {
                    assertThat(view.fiveHour().windowStart()).isEqualTo(start);
                    assertThat(view.fiveHour().used()).isEqualTo(17L);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("手动重置把 start 设为 now 且 used 归零")
    void manualResetUsesNow() {
        Instant start = Instant.parse("2026-09-03T07:00:00Z");
        accessClock.setInstant(start);
        TokenSnapshot token = createToken(60, 100L, 1000L);
        StepVerifier.create(accessApi.recordUsage(token.code(), 20, 0, UsageMeta.empty())).verifyComplete();

        Instant resetAt = Instant.parse("2026-09-03T09:00:00Z");
        accessClock.setInstant(resetAt);
        StepVerifier.create(accessApi.resetQuota(token.code(), QuotaLayer.FIVE_HOUR)).verifyComplete();
        StepVerifier.create(accessApi.getQuota(token.code()))
                .assertNext(view -> {
                    assertThat(view.fiveHour().windowStart()).isEqualTo(resetAt);
                    assertThat(view.fiveHour().used()).isZero();
                    assertThat(view.week().windowStart()).isEqualTo(start);
                    assertThat(view.week().used()).isEqualTo(20L);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("used 等于 limit 放行，超过才 429")
    void rejectsOnlyWhenUsedGreaterThanLimit() {
        Instant start = Instant.parse("2026-09-03T07:00:00Z");
        accessClock.setInstant(start);
        TokenSnapshot token = createToken(60, 10L, 1000L);
        StepVerifier.create(accessApi.recordUsage(token.code(), 10, 0, UsageMeta.empty())).verifyComplete();
        StepVerifier.create(accessApi.checkQuota(token.code())).verifyComplete();
        StepVerifier.create(accessApi.recordUsage(token.code(), 1, 0, UsageMeta.empty())).verifyComplete();
        StepVerifier.create(accessApi.checkQuota(token.code()))
                .expectError(QuotaExceededException.class)
                .verify();
    }

    @Test
    @DisplayName("QPM 超额 429，且不把 TTL 当时钟")
    void qpmExceedsWithoutUsingTtlAsClock() {
        Instant start = Instant.parse("2026-09-03T07:00:00Z");
        accessClock.setInstant(start);
        TokenSnapshot token = createToken(1, 10_000L, 10_000L);
        StepVerifier.create(accessApi.checkQuota(token.code())).verifyComplete();
        StepVerifier.create(accessApi.checkQuota(token.code()))
                .expectError(QuotaExceededException.class)
                .verify();
        accessClock.setInstant(start.plusSeconds(30));
        StepVerifier.create(accessApi.checkQuota(token.code()))
                .expectError(QuotaExceededException.class)
                .verify();
    }

    @Test
    @DisplayName("prompt 与 completion 都是 0 时不写 Redis、不写明细")
    void zeroUsageSkipsRedisAndLedger() {
        Instant start = Instant.parse("2026-09-03T07:00:00Z");
        accessClock.setInstant(start);
        TokenSnapshot token = createToken(60, 100L, 1000L);
        String fiveHourKey = "gw:quota:{" + token.code() + "}:5h";
        StepVerifier.create(accessApi.recordUsage(token.code(), 0, 0, UsageMeta.empty())).verifyComplete();
        StepVerifier.create(accessApi.getQuota(token.code()))
                .assertNext(view -> assertThat(view.fiveHour().used()).isZero())
                .verifyComplete();
        StepVerifier.create(redis.opsForHash().get(fiveHourKey, "used"))
                .assertNext(used -> assertThat(String.valueOf(used)).isEqualTo("0"))
                .verifyComplete();
    }

    private TokenSnapshot createToken(int qpmLimit, long hourlyLimit, long weeklyLimit) {
        String apikeyCode = "apk_quota_" + System.nanoTime();
        PlaceholderApiKeys.insert(jdbcTemplate, apikeyCode);
        var user = userAdminService.create("quota-user", "DATA", true).block();
        return tokenAdminService
                .create(user.code(), apikeyCode, qpmLimit, hourlyLimit, weeklyLimit, true, null)
                .block();
    }
}
