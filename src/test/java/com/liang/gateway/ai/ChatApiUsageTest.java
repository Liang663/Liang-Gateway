package com.liang.gateway.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.liang.gateway.ai.internal.application.ApikeySnapshot;
import com.liang.gateway.ai.internal.application.LlmApikeyAdminService;
import com.liang.gateway.ai.internal.application.LlmModelAdminService;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.test.StepVerifier;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ChatApiUsageTest {

    @Autowired
    private ChatApi chatApi;

    @Autowired
    private LlmApikeyAdminService apikeyAdminService;

    @Autowired
    private LlmModelAdminService modelAdminService;

    @Test
    @DisplayName("非流式根对象 usage 按目录单价四舍五入到分")
    void nonStreamUsageIsPriced() {
        String model = seed("priced-" + System.nanoTime());
        byte[] body =
                """
                {"id":"x","choices":[{"message":{"content":"hi"}}],"usage":{"prompt_tokens":1000000,"completion_tokens":500000}}
                """
                        .getBytes(StandardCharsets.UTF_8);
        StepVerifier.create(chatApi.readUsage(model, body))
                .assertNext(usage -> {
                    assertThat(usage).isPresent();
                    assertThat(usage.get().promptTokens()).isEqualTo(1_000_000L);
                    assertThat(usage.get().completionTokens()).isEqualTo(500_000L);
                    assertThat(usage.get().amountFen()).isEqualTo(400L);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("缺 usage 不能变成 0 成功账单")
    void missingUsageIsEmptyNotZero() {
        String model = seed("missing-" + System.nanoTime());
        byte[] body = """
                {"id":"x","choices":[{"message":{"content":"hi"}}]}
                """.getBytes(StandardCharsets.UTF_8);
        StepVerifier.create(chatApi.readUsage(model, body))
                .assertNext(usage -> {
                    assertThat(usage).isEmpty();
                    assertThat(usage).isNotEqualTo(Optional.of(new ChatUsage(0L, 0L, 0L)));
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("流式末帧空 choices 仍能读到 usage；重复出现用最新值不累加")
    void streamFramesKeepLatestUsageWithoutBuffering() {
        String model = seed("sse-" + System.nanoTime());
        byte[] firstLf =
                """
                data: {"choices":[{"delta":{"content":"hi"}}],"usage":{"prompt_tokens":1,"completion_tokens":1}}

                """
                        .getBytes(StandardCharsets.UTF_8);
        byte[] lastEmptyChoices =
                """
                data: {"id":"x","choices":[],"usage":{"prompt_tokens":3,"completion_tokens":4}}

                """
                        .getBytes(StandardCharsets.UTF_8);
        TokenCounts latest = chatApi.readSseUsageFrame(firstLf).orElseThrow();
        latest = chatApi.readSseUsageFrame(lastEmptyChoices).orElse(latest);
        assertThat(latest.promptTokens()).isEqualTo(3L);
        assertThat(latest.completionTokens()).isEqualTo(4L);
        StepVerifier.create(chatApi.priceUsage(model, latest))
                .assertNext(usage -> {
                    assertThat(usage.promptTokens()).isEqualTo(3L);
                    assertThat(usage.completionTokens()).isEqualTo(4L);
                    assertThat(usage.amountFen()).isNotEqualTo(2L);
                })
                .verifyComplete();

        byte[] firstCrlf =
                "data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}\r\n\r\n"
                        .getBytes(StandardCharsets.UTF_8);
        byte[] lastCrlf =
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":4}}\r\n\r\n"
                        .getBytes(StandardCharsets.UTF_8);
        latest = chatApi.readSseUsageFrame(firstCrlf).orElseThrow();
        latest = chatApi.readSseUsageFrame(lastCrlf).orElse(latest);
        assertThat(latest.promptTokens()).isEqualTo(3L);
        assertThat(latest.completionTokens()).isEqualTo(4L);
        assertThat(chatApi.readSseUsageFrame("data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}".getBytes(StandardCharsets.UTF_8)))
                .isEmpty();
    }

    @Test
    @DisplayName("isFirstContentFrame 只认带内容的 delta")
    void firstContentFrameDetectsContent() {
        assertThat(chatApi.isFirstContentFrame(
                        "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}".getBytes(StandardCharsets.UTF_8)))
                .isTrue();
        assertThat(chatApi.isFirstContentFrame(
                        "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}".getBytes(StandardCharsets.UTF_8)))
                .isFalse();
        assertThat(chatApi.isFirstContentFrame(
                        "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}"
                                .getBytes(StandardCharsets.UTF_8)))
                .isFalse();
    }

    private String seed(String modelName) {
        ApikeySnapshot key = apikeyAdminService
                .create("deepseek-usage", "deepseek", "https://api.deepseek.com", "sk-test-secret-value", true, null)
                .block();
        modelAdminService.create(modelName, "deepseek", key.code(), 200L, 400L, true).block();
        return modelName;
    }
}
