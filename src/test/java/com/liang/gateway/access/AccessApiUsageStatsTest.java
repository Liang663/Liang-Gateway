package com.liang.gateway.access;

import static org.assertj.core.api.Assertions.assertThat;

import com.liang.gateway.access.internal.application.ModelUsageTotals;
import com.liang.gateway.access.internal.application.TokenAdminService;
import com.liang.gateway.access.internal.application.TokenSnapshot;
import com.liang.gateway.access.internal.application.UsageLimitInput;
import com.liang.gateway.access.internal.application.UsageQueryService;
import com.liang.gateway.access.internal.application.UserAdminService;
import com.liang.gateway.access.internal.infrastructure.AccessClock;
import com.liang.gateway.support.MutableClock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

    @AfterEach
    void resetClock() {
        accessClock.reset();
    }

    @Test
    @DisplayName("时日周月总量与明细合计一致，并按模型分组")
    void calendarStatsMatchRecords() {
        Instant now = ZonedDateTime.of(2026, 9, 3, 10, 15, 0, 0, AccessClock.SHANGHAI)
                .toInstant();
        accessClock.setInstant(now);
        TokenSnapshot token = createToken();

        StepVerifier.create(accessApi.recordUsage(token.code(), 3, 4, 10, "deepseek-chat", new UsageMeta("deepseek-chat", "r1")))
                .verifyComplete();
        accessClock.setInstant(now.minusSeconds(3600));
        StepVerifier.create(accessApi.recordUsage(token.code(), 10, 20, 20, "other-model", UsageMeta.empty()))
                .verifyComplete();
        accessClock.setInstant(now.minusSeconds(86_400));
        StepVerifier.create(accessApi.recordUsage(token.code(), 100, 200, 30, "deepseek-chat", UsageMeta.empty()))
                .verifyComplete();
        accessClock.setInstant(now);

        StepVerifier.create(usageQueryService.stats(token.code(), "hour"))
                .assertNext(stats -> {
                    assertThat(stats.promptTokens()).isEqualTo(3L);
                    assertThat(stats.completionTokens()).isEqualTo(4L);
                    assertThat(stats.totalTokens()).isEqualTo(7L);
                    assertThat(stats.amountFen()).isEqualTo(10L);
                    assertThat(stats.byModel()).containsExactly(new ModelUsageTotals("deepseek-chat", 3L, 4L, 7L, 10L));
                })
                .verifyComplete();
        StepVerifier.create(usageQueryService.stats(token.code(), "day"))
                .assertNext(stats -> {
                    assertThat(stats.totalTokens()).isEqualTo(37L);
                    assertThat(stats.amountFen()).isEqualTo(30L);
                    assertThat(stats.byModel())
                            .containsExactly(
                                    new ModelUsageTotals("deepseek-chat", 3L, 4L, 7L, 10L),
                                    new ModelUsageTotals("other-model", 10L, 20L, 30L, 20L));
                })
                .verifyComplete();
        StepVerifier.create(usageQueryService.stats(token.code(), "week"))
                .assertNext(stats -> {
                    assertThat(stats.totalTokens()).isEqualTo(337L);
                    assertThat(stats.amountFen()).isEqualTo(60L);
                })
                .verifyComplete();
        StepVerifier.create(usageQueryService.stats(token.code(), "month"))
                .assertNext(stats -> assertThat(stats.totalTokens()).isEqualTo(337L))
                .verifyComplete();
        StepVerifier.create(usageQueryService.stats(token.code(), "total"))
                .assertNext(stats -> {
                    assertThat(stats.totalTokens()).isEqualTo(337L);
                    assertThat(stats.amountFen()).isEqualTo(60L);
                    long groupedTokens = stats.byModel().stream().mapToLong(ModelUsageTotals::totalTokens).sum();
                    long groupedFen = stats.byModel().stream().mapToLong(ModelUsageTotals::amountFen).sum();
                    assertThat(groupedTokens).isEqualTo(stats.totalTokens());
                    assertThat(groupedFen).isEqualTo(stats.amountFen());
                    assertThat(stats.byModel())
                            .containsExactly(
                                    new ModelUsageTotals("deepseek-chat", 103L, 204L, 307L, 40L),
                                    new ModelUsageTotals("other-model", 10L, 20L, 30L, 20L));
                })
                .verifyComplete();
        StepVerifier.create(usageQueryService.records(token.code(), 10))
                .assertNext(records -> {
                    assertThat(records).hasSize(3);
                    long tokenSum = records.stream().mapToLong(item -> item.totalTokens()).sum();
                    long fenSum = records.stream().mapToLong(item -> item.amountFen()).sum();
                    assertThat(tokenSum).isEqualTo(337L);
                    assertThat(fenSum).isEqualTo(60L);
                })
                .verifyComplete();
    }

    private TokenSnapshot createToken() {
        var user = userAdminService.create("stats-user", "DATA", true).block();
        return tokenAdminService
                .create(
                        user.code(),
                        60,
                        true,
                        null,
                        List.of("deepseek-chat", "other-model"),
                        List.of(new UsageLimitInput(1, 10_000L), new UsageLimitInput(2, 10_000L)))
                .block();
    }
}
