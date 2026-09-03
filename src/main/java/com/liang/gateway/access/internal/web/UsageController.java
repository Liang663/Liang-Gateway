package com.liang.gateway.access.internal.web;

import com.liang.gateway.access.AccessApi;
import com.liang.gateway.access.AccessPrincipal;
import com.liang.gateway.access.QuotaView;
import com.liang.gateway.access.internal.application.AccessNotFoundException;
import com.liang.gateway.access.internal.application.UsageQueryService;
import com.liang.gateway.access.internal.application.UsageRecordSnapshot;
import com.liang.gateway.access.internal.application.UsageStatsSnapshot;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/v1/usage")
public class UsageController {

    private final AccessApi accessApi;
    private final UsageQueryService usageQueryService;

    public UsageController(AccessApi accessApi, UsageQueryService usageQueryService) {
        this.accessApi = accessApi;
        this.usageQueryService = usageQueryService;
    }

    @GetMapping("/quota")
    public Mono<QuotaView> quota() {
        return currentPrincipal().flatMap(principal -> accessApi.getQuota(principal.tokenCode()));
    }

    @GetMapping("/stats")
    public Mono<UsageStatsSnapshot> stats(@RequestParam String range) {
        return currentPrincipal().flatMap(principal -> usageQueryService.stats(principal.tokenCode(), range));
    }

    @GetMapping("/records")
    public Mono<List<UsageRecordSnapshot>> records(@RequestParam(required = false) Integer limit) {
        int pageSize = limit == null ? 100 : limit;
        return currentPrincipal().flatMap(principal -> usageQueryService.records(principal.tokenCode(), pageSize));
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
