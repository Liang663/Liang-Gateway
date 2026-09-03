package com.liang.gateway.support;

import com.liang.gateway.access.internal.application.QuotaWindowStore;
import com.liang.gateway.access.internal.infrastructure.jpa.UserAccessTokenEntity;
import com.liang.gateway.access.internal.infrastructure.jpa.UserAccessTokenRepository;
import com.liang.gateway.access.internal.infrastructure.jpa.UserEntity;
import com.liang.gateway.access.internal.infrastructure.jpa.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class AccessSeedInitializer implements ApplicationRunner {

    private final UserRepository userRepository;
    private final UserAccessTokenRepository tokenRepository;
    private final JdbcTemplate jdbcTemplate;
    private final QuotaWindowStore quotaWindowStore;

    public AccessSeedInitializer(
            UserRepository userRepository,
            UserAccessTokenRepository tokenRepository,
            JdbcTemplate jdbcTemplate,
            QuotaWindowStore quotaWindowStore) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.quotaWindowStore = quotaWindowStore;
    }

    @Override
    public void run(ApplicationArguments args) {
        LocalDateTime now = LocalDateTime.of(2026, 1, 1, 0, 0, 0);
        if (userRepository.findByCode(TestTokens.USER_CODE).isEmpty()) {
            userRepository.save(UserEntity.create(TestTokens.USER_CODE, "core-test", "DATA", true, now));
        }
        Integer apikeyCount = jdbcTemplate.queryForObject(
                "select count(*) from llm_apikey_config where code = ?", Integer.class, TestTokens.APIKEY_CODE);
        if (apikeyCount == null || apikeyCount == 0) {
            jdbcTemplate.update(
                    """
                    INSERT INTO llm_apikey_config
                    (code, name, provider, base_url, secret, prefix, enabled, expire_time, create_time, update_time)
                    VALUES (?, 'core-test', 'deepseek', 'http://127.0.0.1', 'sk-test-placeholder', 'sk-test', 1, NULL, ?, ?)
                    """,
                    TestTokens.APIKEY_CODE,
                    now,
                    now);
        }
        if (tokenRepository.findByAccessToken(TestTokens.ACCESS_TOKEN).isEmpty()) {
            tokenRepository.save(UserAccessTokenEntity.create(
                    TestTokens.TOKEN_CODE,
                    TestTokens.USER_CODE,
                    TestTokens.ACCESS_TOKEN,
                    TestTokens.APIKEY_CODE,
                    true,
                    null,
                    10_000,
                    1_000_000L,
                    1_000_000L,
                    now));
        }
        try {
            quotaWindowStore.openWindows(TestTokens.TOKEN_CODE, Instant.now()).block(Duration.ofSeconds(3));
        } catch (RuntimeException ignored) {
            // Redis-down tests still need the database seed for authentication.
        }
    }
}
