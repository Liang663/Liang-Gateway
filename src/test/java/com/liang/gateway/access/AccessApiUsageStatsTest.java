package com.liang.gateway.access;

import static org.assertj.core.api.Assertions.assertThat;

import com.liang.gateway.access.internal.application.TokenAdminService;
import com.liang.gateway.access.internal.application.TokenSnapshot;
import com.liang.gateway.access.internal.application.UsageQueryService;
import com.liang.gateway.access.internal.application.UserAdminService;
import com.liang.gateway.access.internal.infrastructure.AccessClock;
import com.liang.gateway.access.support.PlaceholderApiKeys;
import com.liang.gateway.support.MutableClock;
import java.time.Instant;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.test.StepVerifier;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AccessApiUsageStatsTest {

    @Autowired
    private AccessApi accessApi;

    @Autowired
    private UsageQueryService usageQueryService;

    @Autowired
    private UserAdminService userAdminService;

    @Autowired
    private TokenAdminService tokenAdminService;

    @Autowired
    private MutableClock accessClock;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void resetClock() {
        accessClock.reset();
    }

    @Test
    @DisplayName("时日周月总量与明细合计一致")
    void calendarStatsMatchRecords() {
        Instant now = ZonedDateTime.of(2026, 9, 3, 10, 15, 0, 0, AccessClock.SHANGHAI)
                .toInstant();
        accessClock.setInstant(now);
        TokenSnapshot token = createToken();

        StepVerifier.create(accessApi.recordUsage(token.code(), 3, 4, new UsageMeta("deepseek-chat", "r1")))
                .verifyComplete();
        accessClock.setInstant(now.minusSeconds(3600));
        StepVerifier.create(accessApi.recordUsage(token.code(), 10, 20, UsageMeta.empty())).verifyComplete();
        accessClock.setInstant(now.minusSeconds(86_400));
        StepVerifier.create(accessApi.recordUsage(token.code(), 100, 200, UsageMeta.empty())).verifyComplete();
        accessClock.setInstant(now);

        StepVerifier.create(usageQueryService.stats(token.code(), "hour"))
                .assertNext(stats -> {
                    assertThat(stats.promptTokens()).isEqualTo(3L);
                    assertThat(stats.completionTokens()).isEqualTo(4L);
                    assertThat(stats.totalTokens()).isEqualTo(7L);
                })
                .verifyComplete();
        StepVerifier.create(usageQueryService.stats(token.code(), "day"))
                .assertNext(stats -> assertThat(stats.totalTokens()).isEqualTo(37L))
                .verifyComplete();
        StepVerifier.create(usageQueryService.stats(token.code(), "week"))
                .assertNext(stats -> assertThat(stats.totalTokens()).isEqualTo(337L))
                .verifyComplete();
        StepVerifier.create(usageQueryService.stats(token.code(), "month"))
                .assertNext(stats -> assertThat(stats.totalTokens()).isEqualTo(337L))
                .verifyComplete();
        StepVerifier.create(usageQueryService.stats(token.code(), "total"))
                .assertNext(stats -> assertThat(stats.totalTokens()).isEqualTo(337L))
                .verifyComplete();
        StepVerifier.create(usageQueryService.records(token.code(), 10))
                .assertNext(records -> {
                    assertThat(records).hasSize(3);
                    long sum = records.stream().mapToLong(item -> item.totalTokens()).sum();
                    assertThat(sum).isEqualTo(337L);
                })
                .verifyComplete();
    }

    private TokenSnapshot createToken() {
        String apikeyCode = "apk_stats_" + System.nanoTime();
        PlaceholderApiKeys.insert(jdbcTemplate, apikeyCode);
        var user = userAdminService.create("stats-user", "DATA", true).block();
        return tokenAdminService
                .create(user.code(), apikeyCode, 60, 10_000L, 10_000L, true, null)
                .block();
    }
}
