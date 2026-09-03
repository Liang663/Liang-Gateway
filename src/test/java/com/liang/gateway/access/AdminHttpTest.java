package com.liang.gateway.access;

import static org.assertj.core.api.Assertions.assertThat;

import com.liang.gateway.access.support.PlaceholderApiKeys;
import com.liang.gateway.support.TestTokens;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class AdminHttpTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("管理面可以维护用户和令牌，并重置额度")
    void adminCrudAndReset() {
        String apikeyCode = "apk_admin_" + System.nanoTime();
        PlaceholderApiKeys.insert(jdbcTemplate, apikeyCode);

        byte[] userBody = webTestClient
                .post()
                .uri("/admin/users")
                .header("X-Admin-Token", TestTokens.ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "admin-user", "authority", "DATA", "enabled", true))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.code")
                .exists()
                .jsonPath("$.name")
                .isEqualTo("admin-user")
                .returnResult()
                .getResponseBody();
        String createdUserCode = extract(userBody, "\"code\":\"", "\"");

        webTestClient
                .get()
                .uri("/admin/users/" + createdUserCode)
                .header("X-Admin-Token", TestTokens.ADMIN_TOKEN)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo(createdUserCode);

        byte[] tokenBody = webTestClient
                .post()
                .uri("/admin/users/" + createdUserCode + "/tokens")
                .header("X-Admin-Token", TestTokens.ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "apikeyCode", apikeyCode,
                        "qpmLimit", 30,
                        "hourlyTokenLimit", 100,
                        "weeklyTokenLimit", 1000,
                        "enabled", true))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.accessToken")
                .exists()
                .jsonPath("$.apikeyCode")
                .isEqualTo(apikeyCode)
                .returnResult()
                .getResponseBody();
        String tokenJson = new String(tokenBody);
        String tokenCode = extract(tokenBody, "\"code\":\"", "\"");
        String accessToken = extract(tokenBody, "\"accessToken\":\"", "\"");
        assertThat(tokenJson).doesNotContain("sk-test-placeholder");

        webTestClient
                .post()
                .uri("/admin/users/" + createdUserCode + "/tokens/" + tokenCode + "/quota/reset")
                .header("X-Admin-Token", TestTokens.ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("layer", "FIVE_HOUR"))
                .exchange()
                .expectStatus()
                .isOk();

        webTestClient
                .get()
                .uri("/v1/usage/quota")
                .header("Authorization", "Bearer " + accessToken)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.fiveHour.used")
                .isEqualTo(0)
                .jsonPath("$.fiveHour.limit")
                .isEqualTo(100)
                .jsonPath("$.week.limit")
                .isEqualTo(1000);
    }

    @Test
    @DisplayName("PUT 省略 enabled 和 expireTime 时保持原值")
    void putOmitsEnabledAndExpireTimeKeepsOriginals() {
        String apikeyCode = "apk_put_" + System.nanoTime();
        PlaceholderApiKeys.insert(jdbcTemplate, apikeyCode);

        byte[] userBody = webTestClient
                .post()
                .uri("/admin/users")
                .header("X-Admin-Token", TestTokens.ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "put-user", "authority", "DATA", "enabled", true))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .returnResult()
                .getResponseBody();
        String userCode = extract(userBody, "\"code\":\"", "\"");

        webTestClient
                .put()
                .uri("/admin/users/" + userCode)
                .header("X-Admin-Token", TestTokens.ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "put-user", "authority", "DATA", "enabled", false))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.enabled")
                .isEqualTo(false);

        webTestClient
                .put()
                .uri("/admin/users/" + userCode)
                .header("X-Admin-Token", TestTokens.ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "put-user-renamed", "authority", "DATA"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.name")
                .isEqualTo("put-user-renamed")
                .jsonPath("$.enabled")
                .isEqualTo(false);

        byte[] tokenBody = webTestClient
                .post()
                .uri("/admin/users/" + userCode + "/tokens")
                .header("X-Admin-Token", TestTokens.ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "apikeyCode",
                        apikeyCode,
                        "qpmLimit",
                        10,
                        "hourlyTokenLimit",
                        100,
                        "weeklyTokenLimit",
                        1000,
                        "enabled",
                        true,
                        "expireTime",
                        "2026-12-01T00:00:00"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .returnResult()
                .getResponseBody();
        String tokenCode = extract(tokenBody, "\"code\":\"", "\"");

        webTestClient
                .put()
                .uri("/admin/users/" + userCode + "/tokens/" + tokenCode)
                .header("X-Admin-Token", TestTokens.ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "qpmLimit", 10, "hourlyTokenLimit", 100, "weeklyTokenLimit", 1000, "enabled", false))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.enabled")
                .isEqualTo(false)
                .jsonPath("$.expireTime")
                .isEqualTo("2026-12-01T00:00:00");

        webTestClient
                .put()
                .uri("/admin/users/" + userCode + "/tokens/" + tokenCode)
                .header("X-Admin-Token", TestTokens.ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("qpmLimit", 20, "hourlyTokenLimit", 200, "weeklyTokenLimit", 2000))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.qpmLimit")
                .isEqualTo(20)
                .jsonPath("$.hourlyTokenLimit")
                .isEqualTo(200)
                .jsonPath("$.weeklyTokenLimit")
                .isEqualTo(2000)
                .jsonPath("$.enabled")
                .isEqualTo(false)
                .jsonPath("$.expireTime")
                .isEqualTo("2026-12-01T00:00:00");
    }

    @Test
    @DisplayName("错误管理令牌不能进管理面")
    void wrongAdminTokenIsUnauthorized() {
        webTestClient
                .get()
                .uri("/admin/users")
                .header("X-Admin-Token", "wrong-admin")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.error.type")
                .isEqualTo("unauthorized");
    }

    private static String extract(byte[] body, String prefix, String suffix) {
        return extract(new String(body == null ? new byte[0] : body), prefix, suffix);
    }

    private static String extract(String json, String prefix, String suffix) {
        int start = json.indexOf(prefix);
        assertThat(start).isGreaterThanOrEqualTo(0);
        start += prefix.length();
        int end = json.indexOf(suffix, start);
        return json.substring(start, end);
    }
}
