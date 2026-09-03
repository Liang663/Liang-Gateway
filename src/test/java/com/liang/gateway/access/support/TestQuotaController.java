package com.liang.gateway.access.support;

import com.liang.gateway.access.AccessApi;
import com.liang.gateway.access.AccessPrincipal;
import com.liang.gateway.access.internal.application.AccessNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
public class TestQuotaController {

    private final AccessApi accessApi;

    public TestQuotaController(AccessApi accessApi) {
        this.accessApi = accessApi;
    }

    @PostMapping("/__test__/quota/check")
    public Mono<Void> check() {
        return currentPrincipal().flatMap(principal -> accessApi.checkQuota(principal.tokenCode()));
    }

    @PostMapping("/__test__/quota/model")
    public Mono<Void> assertModel(@RequestParam String model) {
        return currentPrincipal().flatMap(principal -> accessApi.assertModelAllowed(principal.tokenCode(), model));
    }

    private static Mono<AccessPrincipal> currentPrincipal() {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .mapNotNull(Authentication::getPrincipal)
                .filter(AccessPrincipal.class::isInstance)
                .cast(AccessPrincipal.class)
                .switchIfEmpty(Mono.error(new AccessNotFoundException("Access token not found")));
    }
}
