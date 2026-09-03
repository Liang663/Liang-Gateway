package com.liang.gateway.access;

import com.liang.gateway.access.internal.application.TokenAdminService;
import com.liang.gateway.access.internal.application.TokenSnapshot;
import com.liang.gateway.access.internal.application.UsageLimitInput;
import com.liang.gateway.access.internal.application.UserAdminService;
import com.liang.gateway.support.MutableClock;
import com.liang.gateway.support.TestTokens;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class UsageHttpTest {

    private static final String MODEL = "deepseek-chat";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private AccessApi accessApi;

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
    @DisplayName("调用方只能看到自己令牌的额度条、统计和明细")
    void callerSeesOwnUsage() {
        accessClock.setInstant(Instant.parse("2026-09-03T02:15:00Z"));
        TokenSnapshot token = createToken(60, 10_000L, 10_000L);
        accessApi.recordUsage(token.code(), 5, 7, 12, MODEL, new UsageMeta("deepseek-chat", "req-1")).block();

        webTestClient
                .get()
                .uri("/v1/usage/quota")
                .header("Authorization", "Bearer " + token.accessToken())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.fiveHour.used")
                .isEqualTo(12)
                .jsonPath("$.week.used")
                .isEqualTo(12);

        webTestClient
                .get()
                .uri("/v1/usage/stats?range=total")
                .header("Authorization", "Bearer " + token.accessToken())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.range")
                .isEqualTo("total")
                .jsonPath("$.totalTokens")
                .isEqualTo(12)
                .jsonPath("$.amountFen")
                .isEqualTo(12)
                .jsonPath("$.byModel[0].model")
                .isEqualTo(MODEL)
                .jsonPath("$.byModel[0].amountFen")
                .isEqualTo(12);

        webTestClient
                .get()
                .uri("/v1/usage/records")
                .header("Authorization", "Bearer " + token.accessToken())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[0].totalTokens")
                .isEqualTo(12)
                .jsonPath("$[0].amountFen")
                .isEqualTo(12)
                .jsonPath("$[0].model")
                .isEqualTo("deepseek-chat");
    }

    @Test
    @DisplayName("超额检查返回 429 quota_exceeded")
    void quotaExceededIs429() {
        TokenSnapshot token = createToken(60, 5L, 1000L);
        accessApi.recordUsage(token.code(), 6, 0, 6, MODEL, UsageMeta.empty()).block();

        webTestClient
                .post()
                .uri("/__test__/quota/check")
                .header("Authorization", "Bearer " + token.accessToken())
                .exchange()
                .expectStatus()
                .isEqualTo(429)
                .expectBody()
                .jsonPath("$.error.type")
                .isEqualTo("quota_exceeded");
    }

    @Test
    @DisplayName("未授权模型返回 403 forbidden，不被 429 吃掉")
    void unauthorizedModelIs403() {
        TokenSnapshot token = createToken(60, 10_000L, 10_000L);

        webTestClient
                .post()
                .uri("/__test__/quota/model?model=" + MODEL)
                .header("Authorization", "Bearer " + token.accessToken())
                .exchange()
                .expectStatus()
                .isOk();

        webTestClient
                .post()
                .uri("/__test__/quota/model?model=not-allowed")
                .header("Authorization", "Bearer " + token.accessToken())
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody()
                .jsonPath("$.error.type")
                .isEqualTo("forbidden");
    }

    @Test
    @DisplayName("种子令牌不能看到别人的明细")
    void seedTokenDoesNotSeeOtherRecords() {
        TokenSnapshot token = createToken(60, 10_000L, 10_000L);
        accessApi.recordUsage(token.code(), 9, 1, 9, MODEL, UsageMeta.empty()).block();

        webTestClient
                .get()
                .uri("/v1/usage/records")
                .header("Authorization", TestTokens.BEARER)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[?(@.totalTokens == 10)]")
                .doesNotExist();
    }

    private TokenSnapshot createToken(int qpmLimit, long fiveHourFen, long weekFen) {
        var user = userAdminService.create("http-user", "DATA", true).block();
        return tokenAdminService
                .create(
                        user.code(),
                        qpmLimit,
                        true,
                        null,
                        List.of(MODEL),
                        List.of(new UsageLimitInput(1, fiveHourFen), new UsageLimitInput(2, weekFen)))
                .block();
    }
}
