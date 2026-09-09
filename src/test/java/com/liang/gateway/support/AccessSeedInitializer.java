package com.liang.gateway.support;

import com.liang.gateway.access.QuotaLayer;
import com.liang.gateway.access.internal.application.QuotaWindowStore;
import com.liang.gateway.access.internal.infrastructure.persistence.UsageLimitEntity;
import com.liang.gateway.access.internal.infrastructure.persistence.UsageLimitRepository;
import com.liang.gateway.access.internal.infrastructure.persistence.UserAccessTokenEntity;
import com.liang.gateway.access.internal.infrastructure.persistence.UserAccessTokenRepository;
import com.liang.gateway.access.internal.infrastructure.persistence.UserEntity;
import com.liang.gateway.access.internal.infrastructure.persistence.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class AccessSeedInitializer implements ApplicationRunner {

    private final UserRepository userRepository;
    private final UserAccessTokenRepository tokenRepository;
    private final UsageLimitRepository usageLimitRepository;
    private final QuotaWindowStore quotaWindowStore;

    public AccessSeedInitializer(
            UserRepository userRepository,
            UserAccessTokenRepository tokenRepository,
            UsageLimitRepository usageLimitRepository,
            QuotaWindowStore quotaWindowStore) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.usageLimitRepository = usageLimitRepository;
        this.quotaWindowStore = quotaWindowStore;
    }

    @Override
    public void run(ApplicationArguments args) {
        LocalDateTime now = LocalDateTime.of(2026, 1, 1, 0, 0, 0);
        Duration timeout = Duration.ofSeconds(10);
        userRepository
                .findByCode(TestTokens.USER_CODE)
                .switchIfEmpty(userRepository.save(
                        UserEntity.create(TestTokens.USER_CODE, "core-test", "DATA", true, now)))
                .then(tokenRepository.findByAccessToken(TestTokens.ACCESS_TOKEN).switchIfEmpty(tokenRepository
                        .save(UserAccessTokenEntity.create(
                                TestTokens.TOKEN_CODE,
                                TestTokens.USER_CODE,
                                TestTokens.ACCESS_TOKEN,
                                true,
                                null,
                                10_000,
                                now))
                        .flatMap(token -> usageLimitRepository
                                .save(UsageLimitEntity.create(
                                        TestTokens.USER_CODE, TestTokens.TOKEN_CODE, 1, 1_000_000L, 0L, now))
                                .then(usageLimitRepository.save(UsageLimitEntity.create(
                                        TestTokens.USER_CODE, TestTokens.TOKEN_CODE, 2, 1_000_000L, 0L, now)))
                                .thenReturn(token))))
                .then(quotaWindowStore
                        .openWindows(
                                TestTokens.TOKEN_CODE,
                                List.of(QuotaLayer.FIVE_HOUR, QuotaLayer.WEEK),
                                Instant.now())
                        .onErrorResume(error -> Mono.empty()))
                .block(timeout);
    }
}
