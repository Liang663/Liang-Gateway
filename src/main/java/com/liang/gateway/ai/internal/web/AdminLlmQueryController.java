package com.liang.gateway.ai.internal.web;

import com.liang.gateway.ai.AdminLlmQueryApi;
import java.time.OffsetDateTime;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/admin/llm")
public class AdminLlmQueryController {
    private final AdminLlmQueryApi api;

    public AdminLlmQueryController(AdminLlmQueryApi api) {
        this.api = api;
    }

    @GetMapping("/stats")
    public Mono<AdminLlmQueryApi.Stats> stats(@RequestParam(defaultValue = "24h") String range) {
        return api.stats(range);
    }

    @GetMapping("/call-logs")
    public Mono<AdminLlmQueryApi.LogPage> logs(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String apikeyCode,
            @RequestParam(required = false) Boolean success) {
        return api.callLogs(new AdminLlmQueryApi.LogQuery(page, pageSize, from, to, model, apikeyCode, success));
    }
}
