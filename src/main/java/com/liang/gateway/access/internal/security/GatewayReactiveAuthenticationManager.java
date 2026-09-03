package com.liang.gateway.access.internal.security;

import com.liang.gateway.access.AccessPrincipal;
import com.liang.gateway.access.internal.infrastructure.AccessClock;
import com.liang.gateway.access.internal.infrastructure.jpa.JpaExecutor;
import com.liang.gateway.access.internal.infrastructure.jpa.UserAccessTokenRepository;
import com.liang.gateway.access.internal.infrastructure.jpa.UserEntity;
import com.liang.gateway.access.internal.infrastructure.jpa.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class GatewayReactiveAuthenticationManager implements ReactiveAuthenticationManager {

    private final JpaExecutor jpaExecutor;
    private final UserAccessTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final AccessClock accessClock;
    private final String adminToken;

    public GatewayReactiveAuthenticationManager(
            JpaExecutor jpaExecutor,
            UserAccessTokenRepository tokenRepository,
            UserRepository userRepository,
            AccessClock accessClock,
            @Value("${gateway.admin-token:}") String adminToken) {
        this.jpaExecutor = jpaExecutor;
        this.tokenRepository = tokenRepository;
        this.userRepository = userRepository;
        this.accessClock = accessClock;
        this.adminToken = adminToken == null ? "" : adminToken;
    }

    @Override
    public Mono<Authentication> authenticate(Authentication authentication) {
        if (authentication instanceof AdminAuthenticationToken adminToken) {
            return authenticateAdmin(adminToken);
        }
        if (authentication instanceof AccessAuthenticationToken accessToken) {
            return authenticateAccess(accessToken);
        }
        return Mono.error(new BadCredentialsException("unauthorized"));
    }

    private Mono<Authentication> authenticateAdmin(AdminAuthenticationToken authentication) {
        String provided = String.valueOf(authentication.getCredentials());
        if (adminToken.isEmpty() || !constantTimeEquals(adminToken, provided)) {
            return Mono.error(new BadCredentialsException("unauthorized"));
        }
        return Mono.just(AdminAuthenticationToken.authenticated());
    }

    private Mono<Authentication> authenticateAccess(AccessAuthenticationToken authentication) {
        String raw = String.valueOf(authentication.getCredentials());
        return jpaExecutor
                .call(() -> tokenRepository.findByAccessToken(raw))
                .flatMap(optional -> optional
                        .map(Mono::just)
                        .orElseGet(() -> Mono.error(new BadCredentialsException("unauthorized"))))
                .flatMap(token -> {
                    if (!token.isEnabled() || token.isExpired(accessClock.nowShanghai())) {
                        return Mono.error(new BadCredentialsException("unauthorized"));
                    }
                    return jpaExecutor
                            .call(() -> userRepository.findByCode(token.getUserCode()))
                            .flatMap(userOptional -> {
                                UserEntity user = userOptional.orElse(null);
                                if (user == null || !user.isEnabled()) {
                                    return Mono.error(new BadCredentialsException("unauthorized"));
                                }
                                AccessPrincipal principal =
                                        new AccessPrincipal(user.getCode(), token.getCode(), token.getApikeyCode());
                                return Mono.just(AccessAuthenticationToken.authenticated(principal));
                            });
                });
    }

    private static boolean constantTimeEquals(String expected, String provided) {
        byte[] left = expected.getBytes(StandardCharsets.UTF_8);
        byte[] right = provided.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(left, right);
    }
}
