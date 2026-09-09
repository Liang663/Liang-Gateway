package com.liang.gateway.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.liang.gateway.access.internal.infrastructure.persistence.UsageLimitEntity;
import com.liang.gateway.access.internal.infrastructure.persistence.UsageLimitRepository;
import com.liang.gateway.access.internal.infrastructure.persistence.UserAccessTokenEntity;
import com.liang.gateway.access.internal.infrastructure.persistence.UserAccessTokenModelEntity;
import com.liang.gateway.access.internal.infrastructure.persistence.UserAccessTokenModelRepository;
import com.liang.gateway.access.internal.infrastructure.persistence.UserAccessTokenRepository;
import com.liang.gateway.access.internal.infrastructure.persistence.UserEntity;
import com.liang.gateway.access.internal.infrastructure.persistence.UserRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class UserAccessTokenRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserAccessTokenRepository tokenRepository;

    @Autowired
    private UserAccessTokenModelRepository tokenModelRepository;

    @Autowired
    private UsageLimitRepository usageLimitRepository;

    @Test
    @DisplayName("按 access_token 能查到令牌，禁用和过期字段可读写")
    void findsByAccessTokenAndPersistsFlags() {
        String userCode = "usr_repo_" + System.nanoTime();
        String accessToken = "at_repo_" + System.nanoTime();
        LocalDateTime now = LocalDateTime.of(2026, 9, 3, 10, 0, 0);
        LocalDateTime expireTime = now.plusDays(1);
        userRepository.save(UserEntity.create(userCode, "repo-user", "DATA", true, now)).block();

        UserAccessTokenEntity saved = tokenRepository
                .save(UserAccessTokenEntity.create(
                        "tok_repo_" + System.nanoTime(), userCode, accessToken, false, expireTime, 10, now))
                .block();

        UserAccessTokenEntity found = tokenRepository.findByAccessToken(accessToken).block();
        assertThat(saved).isNotNull();
        assertThat(found).isNotNull();
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
        String accessToken = "at_dup_" + System.nanoTime();
        LocalDateTime now = LocalDateTime.of(2026, 9, 3, 10, 0, 0);
        userRepository.save(UserEntity.create(userCode, "dup-user", "DATA", true, now)).block();
        tokenRepository
                .save(UserAccessTokenEntity.create(
                        "tok_dup_a_" + System.nanoTime(), userCode, accessToken, true, null, 1, now))
                .block();

        assertThatThrownBy(() -> tokenRepository
                        .save(UserAccessTokenEntity.create(
                                "tok_dup_b_" + System.nanoTime(), userCode, accessToken, true, null, 1, now))
                        .block())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("同一令牌不能重复授权同一模型名，同一 limit_type 不能两行")
    void duplicateModelAndLimitTypeRejected() {
        String userCode = "usr_uniq_" + System.nanoTime();
        String tokenCode = "tok_uniq_" + System.nanoTime();
        LocalDateTime now = LocalDateTime.of(2026, 9, 3, 10, 0, 0);
        userRepository.save(UserEntity.create(userCode, "uniq-user", "DATA", true, now)).block();
        tokenRepository
                .save(UserAccessTokenEntity.create(tokenCode, userCode, "at_uniq_" + System.nanoTime(), true, null, 1, now))
                .block();

        tokenModelRepository.save(UserAccessTokenModelEntity.create(tokenCode, "deepseek-chat", now)).block();
        assertThatThrownBy(() -> tokenModelRepository
                        .save(UserAccessTokenModelEntity.create(tokenCode, "deepseek-chat", now))
                        .block())
                .isInstanceOf(DataIntegrityViolationException.class);

        usageLimitRepository.save(UsageLimitEntity.create(userCode, tokenCode, 1, 100L, 0L, now)).block();
        assertThatThrownBy(() -> usageLimitRepository
                        .save(UsageLimitEntity.create(userCode, tokenCode, 1, 200L, 0L, now))
                        .block())
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
