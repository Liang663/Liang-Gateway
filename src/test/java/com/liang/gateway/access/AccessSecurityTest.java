package com.liang.gateway.access;

import static org.assertj.core.api.Assertions.assertThat;

import com.liang.gateway.access.internal.infrastructure.persistence.UserAccessTokenEntity;
import com.liang.gateway.access.internal.infrastructure.persistence.UserAccessTokenRepository;
import com.liang.gateway.access.internal.infrastructure.persistence.UserEntity;
import com.liang.gateway.access.internal.infrastructure.persistence.UserRepository;
import com.liang.gateway.support.TestTokens;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class AccessSecurityTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserAccessTokenRepository tokenRepository;

    @Test
    @DisplayName("GET /health 无 Key 仍 200")
    void healthIsOpen() {
        webTestClient
                .get()
                .uri("/health")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("up");
    }

    @Test
    @DisplayName("无 Key 访问用量接口 401 unauthorized")
    void missingKeyIsUnauthorized() {
        webTestClient
                .get()
                .uri("/v1/usage/quota")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.error.type")
                .isEqualTo("unauthorized");
    }

    @Test
    @DisplayName("错 Key 是 401")
    void wrongKeyIsUnauthorized() {
        webTestClient
                .get()
                .uri("/v1/usage/quota")
                .header(HttpHeaders.AUTHORIZATION, "Bearer wrong-token")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.error.type")
                .isEqualTo("unauthorized");
    }

    @Test
    @DisplayName("api-key 头与 query api_key 可以进门")
    void apiKeyHeaderAndQueryWork() {
        webTestClient
                .get()
                .uri("/v1/usage/quota")
                .header("api-key", TestTokens.ACCESS_TOKEN)
                .exchange()
                .expectStatus()
                .isOk();
        webTestClient
                .get()
                .uri("/v1/usage/quota?api_key=" + TestTokens.ACCESS_TOKEN)
                .exchange()
                .expectStatus()
                .isOk();
        webTestClient
                .get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v1/usage/quota")
                        .queryParam("api_key", " " + TestTokens.ACCESS_TOKEN + " ")
                        .build())
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    @DisplayName("禁用用户或令牌、过期令牌都是 401")
    void disabledOrExpiredIsUnauthorized() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 3, 10, 0, 0);

        String disabledUser = "usr_dis_" + System.nanoTime();
        userRepository.save(UserEntity.create(disabledUser, "disabled", "DATA", false, now)).block();
        String disabledUserToken = "at_dis_user_" + System.nanoTime();
        tokenRepository
                .save(UserAccessTokenEntity.create(
                        "tok_dis_user_" + System.nanoTime(), disabledUser, disabledUserToken, true, null, 10, now))
                .block();

        String enabledUser = "usr_en_" + System.nanoTime();
        userRepository.save(UserEntity.create(enabledUser, "enabled", "DATA", true, now)).block();
        String disabledToken = "at_dis_tok_" + System.nanoTime();
        tokenRepository
                .save(UserAccessTokenEntity.create(
                        "tok_dis_tok_" + System.nanoTime(), enabledUser, disabledToken, false, null, 10, now))
                .block();
        String expiredToken = "at_exp_" + System.nanoTime();
        tokenRepository
                .save(UserAccessTokenEntity.create(
                        "tok_exp_" + System.nanoTime(), enabledUser, expiredToken, true, now.minusDays(1), 10, now))
                .block();

        assertUnauthorized(disabledUserToken);
        assertUnauthorized(disabledToken);
        assertUnauthorized(expiredToken);
        assertThat(tokenRepository.findByAccessToken(expiredToken).block().isExpired(now)).isTrue();
    }

    @Test
    @DisplayName("无管理令牌访问 /admin 是 401")
    void adminWithoutTokenIsUnauthorized() {
        webTestClient
                .get()
                .uri("/admin/users")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.error.type")
                .isEqualTo("unauthorized");
    }

    private void assertUnauthorized(String accessToken) {
        webTestClient
                .get()
                .uri("/v1/usage/quota")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.error.type")
                .isEqualTo("unauthorized");
    }
}
