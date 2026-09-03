package com.liang.gateway.access;

import static org.assertj.core.api.Assertions.assertThat;

import com.liang.gateway.access.internal.application.TokenAdminService;
import com.liang.gateway.access.internal.application.UsageLimitInput;
import com.liang.gateway.access.internal.application.UserAdminService;
import com.liang.gateway.access.internal.infrastructure.jpa.UserAccessTokenEntity;
import com.liang.gateway.access.internal.infrastructure.jpa.UserAccessTokenRepository;
import com.liang.gateway.access.internal.infrastructure.jpa.UserEntity;
import com.liang.gateway.access.internal.infrastructure.jpa.UserRepository;
import com.liang.gateway.support.TestTokens;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.test.StepVerifier;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class AccessApiRedisUnavailableTest {

    @DynamicPropertySource
    static void deadRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> 1);
        registry.add("spring.data.redis.timeout", () -> "500ms");
        registry.add("spring.data.redis.connect-timeout", () -> "500ms");
    }

    @Autowired
    private AccessApi accessApi;

    @Autowired
    private UserAdminService userAdminService;

    @Autowired
    private TokenAdminService tokenAdminService;

    @Autowired
    private UserAccessTokenRepository tokenRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WebTestClient webTestClient;

    @Test
    @DisplayName("检查阶段 Redis 不可用则 HTTP 503 service_unavailable")
    void quotaHttpIs503WhenRedisDown() {
        webTestClient
                .get()
                .uri("/v1/usage/quota")
                .header(HttpHeaders.AUTHORIZATION, TestTokens.BEARER)
                .exchange()
                .expectStatus()
                .isEqualTo(503)
                .expectBody()
                .jsonPath("$.error.type")
                .isEqualTo("service_unavailable");
    }

    @Test
    @DisplayName("检查阶段 Redis 不可用则拒绝，禁止放行")
    void checkQuotaFailsClosedWhenRedisDown() {
        String userCode = "usr_down_" + System.nanoTime();
        String tokenCode = "tok_down_" + System.nanoTime();
        LocalDateTime now = LocalDateTime.of(2026, 9, 3, 10, 0, 0);
        userRepository.save(UserEntity.create(userCode, "down-check", "DATA", true, now));
        tokenRepository.save(UserAccessTokenEntity.create(
                tokenCode, userCode, "at_down_" + System.nanoTime(), true, null, 10, now));

        StepVerifier.create(accessApi.checkQuota(tokenCode))
                .expectError(QuotaStoreUnavailableException.class)
                .verify(Duration.ofSeconds(8));
    }

    @Test
    @DisplayName("创建令牌时 Redis 失败则库里不留下令牌")
    void createTokenRollsBackWhenRedisDown() {
        var user = userAdminService.create("down-user", "DATA", true).block();
        assertThat(user).isNotNull();

        StepVerifier.create(tokenAdminService.create(
                        user.code(),
                        10,
                        true,
                        null,
                        List.of(),
                        List.of(new UsageLimitInput(1, 100L), new UsageLimitInput(2, 1000L))))
                .expectError(QuotaStoreUnavailableException.class)
                .verify(Duration.ofSeconds(8));

        assertThat(tokenRepository.findByUserCodeOrderByCreateTimeDesc(user.code())).isEmpty();
    }
}
