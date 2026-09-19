package com.liang.gateway.ai;

import com.liang.gateway.support.TestTokens;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class AdminLlmQueryHttpTest {
    @Autowired WebTestClient client;

    @Test
    @DisplayName("统计和日志只有管理员可读，错误参数返回400")
    void requiresAdminAndValidatesParameters() {
        for (String path : new String[] {"/admin/llm/stats", "/admin/llm/call-logs"}) {
            client.get().uri(path).exchange().expectStatus().isUnauthorized();
            client.get().uri(path).header("X-Admin-Token", "invalid")
                    .exchange().expectStatus().isUnauthorized();
        }
        for (String path : new String[] {"/admin/llm/stats?range=bad", "/admin/llm/call-logs?page=0",
                "/admin/llm/call-logs?pageSize=0", "/admin/llm/call-logs?from=bad"}) {
            client.get().uri(path).header("X-Admin-Token", TestTokens.ADMIN_TOKEN)
                    .exchange().expectStatus().isBadRequest();
        }
    }

    @Test
    @DisplayName("查询接口返回稳定JSON契约和分页默认值")
    void exposesStatisticsAndPagination() {
        client.get().uri("/admin/llm/stats?range=24h").header("X-Admin-Token", TestTokens.ADMIN_TOKEN)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.total").isNumber().jsonPath("$.successCount").isNumber()
                .jsonPath("$.successRate").isNumber().jsonPath("$.trend").isArray()
                .jsonPath("$.models").isArray();
        client.get().uri("/admin/llm/call-logs?apikeyCode=absent-query-key")
                .header("X-Admin-Token", TestTokens.ADMIN_TOKEN).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.items").isEmpty().jsonPath("$.total").isEqualTo(0)
                .jsonPath("$.page").isEqualTo(1).jsonPath("$.pageSize").isEqualTo(20);
    }
}
