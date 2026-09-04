package com.liang.gateway.ai;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.liang.gateway.ai.AiNotFoundException;
import com.liang.gateway.ai.internal.application.ApikeySnapshot;
import com.liang.gateway.ai.internal.application.LlmApikeyAdminService;
import com.liang.gateway.ai.internal.application.LlmModelAdminService;
import com.liang.gateway.ai.internal.application.ModelSnapshot;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.test.StepVerifier;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ChatApiPrepareTest {

    @Autowired
    private ChatApi chatApi;

    @Autowired
    private LlmApikeyAdminService apikeyAdminService;

    @Autowired
    private LlmModelAdminService modelAdminService;

    @Autowired
    private JsonMapper objectMapper;

    @Test
    @DisplayName("入站多出来的字段仍在出站 body 里，stream=true 时覆盖 include_usage=false")
    void extraFieldsKeptAndIncludeUsageForced() throws Exception {
        Catalog catalog = seed("deepseek-chat-" + System.nanoTime(), true);
        byte[] inbound =
                """
                {"model":"%s","messages":[{"role":"user","content":"hi"}],"temperature":0.2,"foo":"bar","stream":true,"stream_options":{"include_usage":false}}
                """
                        .formatted(catalog.modelName())
                        .getBytes(StandardCharsets.UTF_8);

        StepVerifier.create(chatApi.prepare(catalog.modelName(), inbound))
                .assertNext(upstream -> {
                    assertThat(upstream.url()).isEqualTo("https://api.deepseek.com/chat/completions");
                    assertThat(upstream.stream()).isTrue();
                    assertThat(upstream.extraHeaders())
                            .containsEntry("Authorization", "Bearer sk-test-secret-value")
                            .containsEntry("Content-Type", "application/json");
                    JsonNode body = read(upstream.body());
                    assertThat(body.path("foo").asText()).isEqualTo("bar");
                    assertThat(body.path("temperature").asDouble()).isEqualTo(0.2d);
                    assertThat(body.path("messages").isArray()).isTrue();
                    assertThat(body.path("stream").asBoolean()).isTrue();
                    assertThat(body.path("stream_options").path("include_usage").asBoolean()).isTrue();
                    assertThat(upstream.apikeyCode()).isEqualTo(catalog.apikeyCode());
                    assertThat(upstream.toString()).doesNotContain("sk-test-secret-value");
                    assertThat(upstream.toString()).contains("Bearer ***");
                    assertThat(upstream.toString()).contains(catalog.apikeyCode());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("stream=false 时出站 body 是入站原字节")
    void nonStreamBodyIsPassThrough() {
        Catalog catalog = seed("deepseek-sync-" + System.nanoTime(), true);
        byte[] inbound =
                """
                {"model":"%s","messages":[{"role":"user","content":"hi"}],"foo":"bar","stream":false}
                """
                        .formatted(catalog.modelName())
                        .getBytes(StandardCharsets.UTF_8);
        StepVerifier.create(chatApi.prepare(catalog.modelName(), inbound))
                .assertNext(upstream -> {
                    assertThat(upstream.stream()).isFalse();
                    assertThat(Arrays.equals(upstream.body(), inbound)).isTrue();
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("stream=true 时无论入站有无 stream_options，出站都是 include_usage=true")
    void streamTrueAlwaysIncludesUsage() {
        Catalog catalog = seed("deepseek-missing-" + System.nanoTime(), true);
        byte[] noOptions =
                """
                {"model":"%s","messages":[],"stream":true}
                """
                        .formatted(catalog.modelName())
                        .getBytes(StandardCharsets.UTF_8);
        StepVerifier.create(chatApi.prepare(catalog.modelName(), noOptions))
                .assertNext(upstream -> assertThat(read(upstream.body())
                                .path("stream_options")
                                .path("include_usage")
                                .asBoolean())
                        .isTrue())
                .verifyComplete();

        byte[] alreadyTrue =
                """
                {"model":"%s","messages":[],"stream":true,"stream_options":{"include_usage":true}}
                """
                        .formatted(catalog.modelName())
                        .getBytes(StandardCharsets.UTF_8);
        StepVerifier.create(chatApi.prepare(catalog.modelName(), alreadyTrue))
                .assertNext(upstream -> assertThat(read(upstream.body())
                                .path("stream_options")
                                .path("include_usage")
                                .asBoolean())
                        .isTrue())
                .verifyComplete();
    }

    @Test
    @DisplayName("未知或禁用模型失败，且不依赖 access")
    void unknownOrDisabledModelFails() {
        Catalog disabled = seed("deepseek-off-" + System.nanoTime(), false);
        StepVerifier.create(chatApi.prepare("no-such-model", "{\"stream\":true}".getBytes(StandardCharsets.UTF_8)))
                .expectError(AiNotFoundException.class)
                .verify();
        StepVerifier.create(chatApi.prepare(disabled.modelName(), "{\"stream\":true}".getBytes(StandardCharsets.UTF_8)))
                .expectError(AiNotFoundException.class)
                .verify();
    }

    private Catalog seed(String modelName, boolean enabled) {
        ApikeySnapshot key = apikeyAdminService
                .create("deepseek-main", "deepseek", "https://api.deepseek.com/", "sk-test-secret-value", true, null)
                .block();
        ModelSnapshot model = modelAdminService
                .create(modelName, "deepseek", key.code(), 200L, 400L, enabled)
                .block();
        return new Catalog(key.code(), model.name());
    }

    private JsonNode read(byte[] body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private record Catalog(String apikeyCode, String modelName) {}
}
