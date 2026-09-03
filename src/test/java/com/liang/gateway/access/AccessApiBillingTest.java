package com.liang.gateway.access;

import static org.assertj.core.api.Assertions.assertThat;

import com.liang.gateway.access.internal.application.TokenAdminService;
import com.liang.gateway.access.internal.application.TokenSnapshot;
import com.liang.gateway.access.internal.application.UsageLimitInput;
import com.liang.gateway.access.internal.application.UserAdminService;
import com.liang.gateway.support.MutableClock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.test.StepVerifier;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AccessApiBillingTest {

    private static final String MODEL = "deepseek-chat";

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
    @DisplayName("type=0 不限额，金额再大也不 429，且不打开金额 Redis 窗")
    void unlimitedSkipsAmountWindow() {
        Instant start = Instant.parse("2026-09-03T07:00:00Z");
        accessClock.setInstant(start);
        TokenSnapshot token = createToken(60, List.of(new UsageLimitInput(0, 0)));

        StepVerifier.create(accessApi.recordUsage(token.code(), 1, 0, 1_000_000L, MODEL, UsageMeta.empty()))
                .verifyComplete();
        StepVerifier.create(accessApi.checkQuota(token.code())).verifyComplete();
        StepVerifier.create(accessApi.getQuota(token.code()))
                .assertNext(view -> {
                    assertThat(view.fiveHour().used()).isZero();
                    assertThat(view.fiveHour().limit()).isZero();
                    assertThat(view.week().used()).isZero();
                    assertThat(view.week().limit()).isZero();
                })
                .verifyComplete();
        StepVerifier.create(redis.hasKey("gw:quota:{" + token.code() + "}:5h"))
                .assertNext(exists -> assertThat(exists).isFalse())
                .verifyComplete();
        StepVerifier.create(redis.hasKey("gw:quota:{" + token.code() + "}:week"))
                .assertNext(exists -> assertThat(exists).isFalse())
                .verifyComplete();
    }

    @Test
    @DisplayName("type=0 仍受 QPM 429")
    void unlimitedStillEnforcesQpm() {
        Instant start = Instant.parse("2026-09-03T07:00:00Z");
        accessClock.setInstant(start);
        TokenSnapshot token = createToken(1, List.of(new UsageLimitInput(0, 0)));
        StepVerifier.create(accessApi.checkQuota(token.code())).verifyComplete();
        StepVerifier.create(accessApi.checkQuota(token.code()))
                .expectError(QuotaExceededException.class)
                .verify();
    }

    @Test
    @DisplayName("type 1/2 超额按分 429")
    void windowLimitExceedsInFen() {
        Instant start = Instant.parse("2026-09-03T07:00:00Z");
        accessClock.setInstant(start);
        TokenSnapshot token = createToken(
                60, List.of(new UsageLimitInput(1, 100L), new UsageLimitInput(2, 10_000L)));
        StepVerifier.create(accessApi.recordUsage(token.code(), 1, 0, 100, MODEL, UsageMeta.empty()))
                .verifyComplete();
        StepVerifier.create(accessApi.checkQuota(token.code())).verifyComplete();
        StepVerifier.create(accessApi.recordUsage(token.code(), 1, 0, 1, MODEL, UsageMeta.empty()))
                .verifyComplete();
        StepVerifier.create(accessApi.checkQuota(token.code()))
                .expectError(QuotaExceededException.class)
                .verify();
    }

    @Test
    @DisplayName("未授权模型 403，授权模型通过，列表只返回模型名")
    void unauthorizedModelIsForbidden() {
        TokenSnapshot token = createToken(
                60,
                List.of("deepseek-chat", "deepseek-reasoner"),
                List.of(new UsageLimitInput(1, 10_000L), new UsageLimitInput(2, 10_000L)));
        StepVerifier.create(accessApi.assertModelAllowed(token.code(), "deepseek-chat")).verifyComplete();
        StepVerifier.create(accessApi.assertModelAllowed(token.code(), "not-allowed"))
                .expectError(ModelForbiddenException.class)
                .verify();
        StepVerifier.create(accessApi.listAllowedModels(token.code()))
                .assertNext(models -> assertThat(models).containsExactly("deepseek-chat", "deepseek-reasoner"))
                .verifyComplete();
    }

    @Test
    @DisplayName("recordUsage 一次加上本笔分，100 分加一次 used=100")
    void recordUsageIncrementsAmountOnce() {
        Instant start = Instant.parse("2026-09-03T07:00:00Z");
        accessClock.setInstant(start);
        TokenSnapshot token = createToken(
                60, List.of(new UsageLimitInput(1, 10_000L), new UsageLimitInput(2, 10_000L)));
        StepVerifier.create(accessApi.recordUsage(token.code(), 10, 20, 100, MODEL, UsageMeta.empty()))
                .verifyComplete();
        StepVerifier.create(accessApi.getQuota(token.code()))
                .assertNext(view -> {
                    assertThat(view.fiveHour().used()).isEqualTo(100L);
                    assertThat(view.week().used()).isEqualTo(100L);
                })
                .verifyComplete();
        StepVerifier.create(redis.opsForHash().get("gw:quota:{" + token.code() + "}:5h", "used"))
                .assertNext(used -> assertThat(String.valueOf(used)).isEqualTo("100"))
                .verifyComplete();
    }

    @Test
    @DisplayName("回写 usage_limit.used 与 Redis 一致，窗口清零同样回写 0")
    void usedIsWrittenBackToMysql() {
        Instant start = Instant.parse("2026-09-03T07:00:00Z");
        accessClock.setInstant(start);
        TokenSnapshot token = createToken(
                60, List.of(new UsageLimitInput(1, 10_000L), new UsageLimitInput(2, 10_000L)));
        StepVerifier.create(accessApi.recordUsage(token.code(), 1, 0, 100, MODEL, UsageMeta.empty()))
                .verifyComplete();
        assertThat(mysqlUsed(token.code(), 1)).isEqualTo(100L);
        assertThat(mysqlUsed(token.code(), 2)).isEqualTo(100L);

        accessClock.setInstant(Instant.parse("2026-09-03T14:00:00Z"));
        StepVerifier.create(accessApi.getQuota(token.code()))
                .assertNext(view -> assertThat(view.fiveHour().used()).isZero())
                .verifyComplete();
        assertThat(mysqlUsed(token.code(), 1)).isZero();
        assertThat(mysqlUsed(token.code(), 2)).isEqualTo(100L);
    }

    private Long mysqlUsed(String tokenCode, int limitType) {
        return jdbcTemplate.queryForObject(
                "select used from usage_limit where token_code = ? and limit_type = ?",
                Long.class,
                tokenCode,
                limitType);
    }

    private TokenSnapshot createToken(int qpmLimit, List<UsageLimitInput> limits) {
        return createToken(qpmLimit, List.of(MODEL), limits);
    }

    private TokenSnapshot createToken(int qpmLimit, List<String> models, List<UsageLimitInput> limits) {
        var user = userAdminService.create("billing-user", "DATA", true).block();
        return tokenAdminService.create(user.code(), qpmLimit, true, null, models, limits).block();
    }
}
