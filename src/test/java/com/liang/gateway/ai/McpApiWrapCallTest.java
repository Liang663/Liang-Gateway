package com.liang.gateway.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class McpApiWrapCallTest {

    @Autowired
    private McpApi mcpApi;

    @Test
    @DisplayName("2xx 把上游正文写入 content 且 isError=false")
    void successUsesUpstreamBody() {
        McpCallResult result = mcpApi.wrapCall(200, "{\"ok\":true}", false);
        assertThat(result.isError()).isFalse();
        assertThat(result.text()).isEqualTo("{\"ok\":true}");
        assertThat(result.content().getFirst().type()).isEqualTo("text");
        assertThat(result.resultType()).isEqualTo("complete");
    }

    @Test
    @DisplayName("4xx 文本含状态短语和 body 摘要，不是光秃状态码")
    void clientErrorIncludesStatusAndBody() {
        McpCallResult result = mcpApi.wrapCall(404, "{\"error\":\"nope\"}", false);
        assertThat(result.isError()).isTrue();
        assertThat(result.text()).startsWith("upstream_error:");
        assertThat(result.text()).contains("HTTP 404");
        assertThat(result.text()).contains("Not Found");
        assertThat(result.text()).contains("{\"error\":\"nope\"}");
        assertThat(result.text()).isNotEqualTo("404");
        assertThat(result.text()).doesNotContain("sk-secret");
    }

    @Test
    @DisplayName("超时使用 upstream_timeout 前缀")
    void timeoutUsesPrefix() {
        McpCallResult result = mcpApi.wrapCall(200, "ignored", true);
        assertThat(result.isError()).isTrue();
        assertThat(result.text()).startsWith("upstream_timeout:");
        assertThat(result.text()).contains("上游超时");
    }

    @Test
    @DisplayName("非 2xx 正文超过 512 会被截断")
    void errorBodyIsTruncated() {
        String body = "x".repeat(600);
        McpCallResult result = mcpApi.wrapCall(500, body, false);
        assertThat(result.text()).startsWith("upstream_error:");
        assertThat(result.text()).contains("HTTP 500");
        assertThat(result.text()).contains("body=" + "x".repeat(512));
        assertThat(result.text()).doesNotContain("x".repeat(513));
    }
}
