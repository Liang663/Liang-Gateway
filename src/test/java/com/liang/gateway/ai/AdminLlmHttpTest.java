package com.liang.gateway.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.liang.gateway.support.TestTokens;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class AdminLlmHttpTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ChatApi chatApi;

    @Test
    @DisplayName("管理面可登记 Key 与模型，响应不含 secret，统计挂在 Key 下")
    void adminCanRegisterKeyAndModel() {
        byte[] keyBody = webTestClient
                .post()
                .uri("/admin/llm/apikeys")
                .header("X-Admin-Token", TestTokens.ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "name",
                        "deepseek-main",
                        "provider",
                        "deepseek",
                        "baseUrl",
                        "https://api.deepseek.com",
                        "secret",
                        "sk-test-secret-value",
                        "enabled",
                        true))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.code")
                .exists()
                .jsonPath("$.prefix")
                .isEqualTo("sk-test-")
                .jsonPath("$.secret")
                .doesNotExist()
                .returnResult()
                .getResponseBody();
        String keyJson = new String(keyBody == null ? new byte[0] : keyBody);
        assertThat(keyJson).doesNotContain("sk-test-secret-value");
        String keyCode = extract(keyJson, "\"code\":\"", "\"");

        String modelName = "deepseek-v4-flash-" + System.nanoTime();
        webTestClient
                .post()
                .uri("/admin/llm/models")
                .header("X-Admin-Token", TestTokens.ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "name",
                        modelName,
                        "provider",
                        "deepseek",
                        "apikeyCode",
                        keyCode,
                        "inputPriceFenPerMillion",
                        200,
                        "outputPriceFenPerMillion",
                        400,
                        "enabled",
                        true))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.name")
                .isEqualTo(modelName)
                .jsonPath("$.apikeyCode")
                .isEqualTo(keyCode)
                .jsonPath("$.inputPriceFenPerMillion")
                .isEqualTo(200);

        chatApi.recordCallLog(keyCode, modelName, true, "ok", 80, 200).block();

        webTestClient
                .get()
                .uri("/admin/llm/apikeys/" + keyCode + "/stats")
                .header("X-Admin-Token", TestTokens.ADMIN_TOKEN)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.total")
                .isEqualTo(1)
                .jsonPath("$.successCount")
                .isEqualTo(1)
                .jsonPath("$.averageFirstTokenMs")
                .isEqualTo(80.0)
                .jsonPath("$.failureRate")
                .isEqualTo(0.0);
    }

    private static String extract(String json, String prefix, String suffix) {
        int start = json.indexOf(prefix);
        assertThat(start).isGreaterThanOrEqualTo(0);
        start += prefix.length();
        int end = json.indexOf(suffix, start);
        return json.substring(start, end);
    }
}
