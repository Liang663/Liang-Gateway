package com.liang.gateway.core;

import com.liang.gateway.core.support.TestPipelineConfiguration;
import com.liang.gateway.support.TestTokens;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "gateway.proxy.connect-timeout=200ms",
            "gateway.proxy.response-timeout=200ms"
        })
@AutoConfigureWebTestClient(timeout = "10s")
@Import(TestPipelineConfiguration.class)
class WebClientProxyTimeoutTest {

    @Autowired
    private WebTestClient webTestClient;

    private MockWebServer mockWebServer;

    @BeforeEach
    void authorize() {
        webTestClient = webTestClient.mutate()
                .defaultHeader(HttpHeaders.AUTHORIZATION, TestTokens.BEARER)
                .build();
    }

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @Test
    @DisplayName("上游超时映射 504 bad_gateway")
    void upstreamTimeoutMappedToGatewayTimeout() {
        mockWebServer.enqueue(new MockResponse()
                .setHeadersDelay(5, TimeUnit.SECONDS)
                .setBody("too-late"));

        webTestClient
                .get()
                .uri("/__test__/pipeline")
                .header("X-Upstream-Url", mockWebServer.url("/slow").toString())
                .header("X-Upstream-Stream", "false")
                .exchange()
                .expectStatus()
                .isEqualTo(504)
                .expectBody()
                .jsonPath("$.error.type")
                .isEqualTo("bad_gateway")
                .jsonPath("$.error.message")
                .isEqualTo("Gateway timeout");
    }
}
