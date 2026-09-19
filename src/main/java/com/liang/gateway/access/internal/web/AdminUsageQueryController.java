package com.liang.gateway.access.internal.web;

import com.liang.gateway.access.AdminUsageQueryApi;
import com.liang.gateway.access.QuotaView;
import java.time.OffsetDateTime;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
public class AdminUsageQueryController {

    private final AdminUsageQueryApi queries;

    public AdminUsageQueryController(AdminUsageQueryApi queries) {
        this.queries = queries;
    }

    @GetMapping("/admin/usage/stats")
    public Mono<AdminUsageQueryApi.Stats> stats(
            @RequestParam(defaultValue = "24h") String range,
            @RequestParam(required = false) String userCode,
            @RequestParam(required = false) String tokenCode,
            @RequestParam(required = false) String model) {
        return queries.stats(range, userCode, tokenCode, model);
    }

    @GetMapping("/admin/usage/records")
    public Mono<AdminUsageQueryApi.Page> records(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) String userCode,
            @RequestParam(required = false) String tokenCode,
            @RequestParam(required = false) String model) {
        return queries.records(new AdminUsageQueryApi.RecordQuery(page, pageSize, from, to, userCode, tokenCode, model));
    }

    @GetMapping("/admin/users/{userCode}/tokens/{tokenCode}/quota")
    public Mono<QuotaView> quota(@PathVariable String userCode, @PathVariable String tokenCode) {
        return queries.quota(userCode, tokenCode);
    }
}
