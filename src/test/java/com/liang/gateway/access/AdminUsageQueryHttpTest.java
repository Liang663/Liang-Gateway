package com.liang.gateway.access;

import static org.assertj.core.api.Assertions.assertThat;

import com.liang.gateway.access.internal.application.TokenAdminService;
import com.liang.gateway.access.internal.application.TokenSnapshot;
import com.liang.gateway.access.internal.application.UsageLimitInput;
import com.liang.gateway.access.internal.application.UserAdminService;
import com.liang.gateway.access.internal.infrastructure.AccessClock;
import com.liang.gateway.access.internal.infrastructure.persistence.UsageRecordEntity;
import com.liang.gateway.access.internal.infrastructure.persistence.UsageRecordRepository;
import com.liang.gateway.support.MutableClock;
import com.liang.gateway.support.TestTokens;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class AdminUsageQueryHttpTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 18, 12, 0);

    @Autowired private WebTestClient client;
    @Autowired private UserAdminService users;
    @Autowired private TokenAdminService tokens;
    @Autowired private UsageRecordRepository records;
    @Autowired private MutableClock clock;
    @Autowired private AccessApi access;

    private TokenSnapshot token;

    @BeforeEach
    void setUp() {
        clock.setInstant(NOW.atZone(AccessClock.SHANGHAI).toInstant());
        var user = users.create("console-usage-test", "DATA", true).block();
        token = tokens.create(user.code(), 60, true, null, List.of("console-model"),
                List.of(new UsageLimitInput(1, 1000), new UsageLimitInput(2, 2000))).block();
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    @DisplayName("管理统计在数据库按时间和用户令牌模型聚合，区间左闭右开")
    void aggregatesFilteredWindowWithExclusiveEnd() {
        save("console-model", NOW.minusHours(24), 10, 20, 3);
        save("console-model", NOW.minusHours(1), 2, 3, 4);
        save("other-model", NOW.minusHours(1), 100, 100, 100);
        save("console-model", NOW.minusHours(25), 1000, 1000, 1000);
        save("console-model", NOW, 1000, 1000, 1000);

        client.get().uri(builder -> builder.path("/admin/usage/stats")
                        .queryParam("range", "24h").queryParam("userCode", token.userCode())
                        .queryParam("tokenCode", token.code()).queryParam("model", "console-model").build())
                .header("X-Admin-Token", TestTokens.ADMIN_TOKEN).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.totalRecords").isEqualTo(2)
                .jsonPath("$.promptTokens").isEqualTo(12).jsonPath("$.completionTokens").isEqualTo(23)
                .jsonPath("$.totalTokens").isEqualTo(35).jsonPath("$.amountFen").isEqualTo(7);

        client.get().uri(builder -> builder.path("/admin/usage/stats")
                        .queryParam("range", "7d").queryParam("tokenCode", token.code())
                        .queryParam("model", "console-model").build())
                .header("X-Admin-Token", TestTokens.ADMIN_TOKEN).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.totalRecords").isEqualTo(3).jsonPath("$.amountFen").isEqualTo(1007);
    }

    @Test
    @DisplayName("账单分页使用上海时区解释带偏移量时间并稳定排序")
    void pagesRecordsWithStableOrderingAndOffsetDates() {
        save("console-model", NOW.minusHours(2), 1, 2, 3);
        String newer = save("console-model", NOW.minusHours(1), 4, 5, 6);
        String newest = save("console-model", NOW.minusHours(1), 7, 8, 9);
        save("console-model", NOW, 50, 50, 50);

        client.get().uri(builder -> builder.path("/admin/usage/records")
                        .queryParam("tokenCode", token.code()).queryParam("page", 1).queryParam("pageSize", 1)
                        .queryParam("from", "2026-09-17T04:00:00Z").queryParam("to", "2026-09-18T04:00:00Z").build())
                .header("X-Admin-Token", TestTokens.ADMIN_TOKEN).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.total").isEqualTo(3).jsonPath("$.page").isEqualTo(1)
                .jsonPath("$.pageSize").isEqualTo(1).jsonPath("$.items.length()").isEqualTo(1)
                .jsonPath("$.items[0].code").isEqualTo(newest)
                .jsonPath("$.items[0].createTime").isEqualTo("2026-09-18T11:00:00")
                .jsonPath("$.items[0].requestId").isEqualTo("console-request");

        client.get().uri(builder -> builder.path("/admin/usage/records").queryParam("tokenCode", token.code())
                        .queryParam("page", 2).queryParam("pageSize", 1).build())
                .header("X-Admin-Token", TestTokens.ADMIN_TOKEN).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.total").isEqualTo(3).jsonPath("$.items[0].code").isEqualTo(newer);

        client.get().uri(builder -> builder.path("/admin/usage/records").queryParam("tokenCode", token.code())
                        .queryParam("page", 5).queryParam("pageSize", 1).build())
                .header("X-Admin-Token", TestTokens.ADMIN_TOKEN).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.total").isEqualTo(3).jsonPath("$.items").isEmpty();
    }

    @Test
    @DisplayName("空统计返回零值，账单筛选不能绕过用户和模型条件")
    void emptyAndCombinedFilters() {
        save("console-model", NOW.minusMinutes(1), 1, 2, 3);
        client.get().uri(builder -> builder.path("/admin/usage/stats").queryParam("userCode", "missing-user").build())
                .header("X-Admin-Token", TestTokens.ADMIN_TOKEN).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.totalRecords").isEqualTo(0).jsonPath("$.amountFen").isEqualTo(0)
                .jsonPath("$.totalTokens").isEqualTo(0);
        client.get().uri(builder -> builder.path("/admin/usage/records").queryParam("tokenCode", token.code())
                        .queryParam("userCode", "missing-user").queryParam("model", "console-model").build())
                .header("X-Admin-Token", TestTokens.ADMIN_TOKEN).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.total").isEqualTo(0).jsonPath("$.items").isEmpty();
    }

    @Test
    @DisplayName("查询拒绝非法分页和时间区间，并限制单页上限")
    void validatesQueryAndCapsPageSize() {
        for (String query : List.of("page=0", "pageSize=0", "from=2026-09-19T00:00:00Z&to=2026-09-18T00:00:00Z",
                "from=2026-09-18T00:00:00Z&to=2026-09-18T00:00:00Z", "from=invalid")) {
            client.get().uri("/admin/usage/records?" + query).header("X-Admin-Token", TestTokens.ADMIN_TOKEN)
                    .exchange().expectStatus().isBadRequest();
        }
        client.get().uri("/admin/usage/stats?range=month").header("X-Admin-Token", TestTokens.ADMIN_TOKEN)
                .exchange().expectStatus().isBadRequest();
        client.get().uri(builder -> builder.path("/admin/usage/records").queryParam("tokenCode", token.code())
                        .queryParam("pageSize", 200).build())
                .header("X-Admin-Token", TestTokens.ADMIN_TOKEN).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.pageSize").isEqualTo(100);
    }

    @Test
    @DisplayName("管理额度读取真实窗口并校验用户令牌归属")
    void quotaChecksOwnershipAndReturnsRealWindow() {
        access.recordUsage(token.code(), 3, 4, 11, "console-model", UsageMeta.empty()).block();
        client.get().uri("/admin/users/" + token.userCode() + "/tokens/" + token.code() + "/quota")
                .header("X-Admin-Token", TestTokens.ADMIN_TOKEN).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.fiveHour.used").isEqualTo(11).jsonPath("$.fiveHour.limit").isEqualTo(1000)
                .jsonPath("$.week.used").isEqualTo(11).jsonPath("$.week.limit").isEqualTo(2000);
        var another = users.create("console-other", "DATA", true).block();
        client.get().uri("/admin/users/" + another.code() + "/tokens/" + token.code() + "/quota")
                .header("X-Admin-Token", TestTokens.ADMIN_TOKEN).exchange().expectStatus().isNotFound();
    }

    @Test
    @DisplayName("新增查询接口均要求管理员凭据")
    void requiresAdminCredentials() {
        for (String path : List.of("/admin/usage/stats", "/admin/usage/records",
                "/admin/users/" + token.userCode() + "/tokens/" + token.code() + "/quota")) {
            client.get().uri(path).exchange().expectStatus().isUnauthorized();
            client.get().uri(path).header("X-Admin-Token", "wrong-admin").exchange().expectStatus().isUnauthorized();
            client.get().uri(path).header("Authorization", "Bearer " + token.accessToken())
                    .exchange().expectStatus().isUnauthorized();
        }
    }

    private String save(String model, LocalDateTime time, long prompt, long completion, long amount) {
        String code = UUID.randomUUID().toString();
        var saved = records.save(UsageRecordEntity.create(code, token.code(), token.userCode(),
                prompt, completion, amount, model, "console-request", time)).block();
        assertThat(saved).isNotNull();
        return code;
    }
}
