package com.liang.gateway.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.liang.gateway.access.internal.infrastructure.jpa.UserAccessTokenEntity;
import com.liang.gateway.access.internal.infrastructure.jpa.UserAccessTokenRepository;
import com.liang.gateway.access.internal.infrastructure.jpa.UserEntity;
import com.liang.gateway.access.internal.infrastructure.jpa.UserRepository;
import com.liang.gateway.access.support.PlaceholderApiKeys;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class UserAccessTokenRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserAccessTokenRepository tokenRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("按 access_token 能查到令牌，禁用和过期字段可读写")
    void findsByAccessTokenAndPersistsFlags() {
        String userCode = "usr_repo_" + System.nanoTime();
        String apikeyCode = "apk_repo_" + System.nanoTime();
        String accessToken = "at_repo_" + System.nanoTime();
        LocalDateTime now = LocalDateTime.of(2026, 9, 3, 10, 0, 0);
        LocalDateTime expireTime = now.plusDays(1);
        userRepository.save(UserEntity.create(userCode, "repo-user", "DATA", true, now));
        PlaceholderApiKeys.insert(jdbcTemplate, apikeyCode);

        UserAccessTokenEntity saved = tokenRepository.save(UserAccessTokenEntity.create(
                "tok_repo_" + System.nanoTime(),
                userCode,
                accessToken,
                apikeyCode,
                false,
                expireTime,
                10,
                100L,
                1000L,
                now));

        UserAccessTokenEntity found = tokenRepository.findByAccessToken(accessToken).orElseThrow();
        assertThat(found.getCode()).isEqualTo(saved.getCode());
        assertThat(found.isEnabled()).isFalse();
        assertThat(found.getExpireTime()).isEqualTo(expireTime);
        assertThat(found.isExpired(now.plusDays(2))).isTrue();
        assertThat(found.isExpired(now)).isFalse();
    }

    @Test
    @DisplayName("同一 access_token 不能插两行")
    void duplicateAccessTokenRejected() {
        String userCode = "usr_dup_" + System.nanoTime();
        String apikeyCode = "apk_dup_" + System.nanoTime();
        String accessToken = "at_dup_" + System.nanoTime();
        LocalDateTime now = LocalDateTime.of(2026, 9, 3, 10, 0, 0);
        userRepository.save(UserEntity.create(userCode, "dup-user", "DATA", true, now));
        PlaceholderApiKeys.insert(jdbcTemplate, apikeyCode);
        tokenRepository.save(UserAccessTokenEntity.create(
                "tok_dup_a_" + System.nanoTime(), userCode, accessToken, apikeyCode, true, null, 1, 1L, 1L, now));

        assertThatThrownBy(() -> tokenRepository.saveAndFlush(UserAccessTokenEntity.create(
                        "tok_dup_b_" + System.nanoTime(),
                        userCode,
                        accessToken,
                        apikeyCode,
                        true,
                        null,
                        1,
                        1L,
                        1L,
                        now)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("令牌的 apikey_code 必须能关联到已有行")
    void missingApikeyRejected() {
        String userCode = "usr_fk_" + System.nanoTime();
        LocalDateTime now = LocalDateTime.of(2026, 9, 3, 10, 0, 0);
        userRepository.save(UserEntity.create(userCode, "fk-user", "DATA", true, now));

        assertThatThrownBy(() -> tokenRepository.saveAndFlush(UserAccessTokenEntity.create(
                        "tok_fk_" + System.nanoTime(),
                        userCode,
                        "at_fk_" + System.nanoTime(),
                        "missing-apikey",
                        true,
                        null,
                        1,
                        1L,
                        1L,
                        now)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
