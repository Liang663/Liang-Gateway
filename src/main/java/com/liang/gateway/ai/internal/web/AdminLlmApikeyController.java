package com.liang.gateway.ai.internal.web;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.liang.gateway.ai.CallLogStats;
import com.liang.gateway.ai.ChatApi;
import com.liang.gateway.ai.internal.application.ApikeySnapshot;
import com.liang.gateway.ai.internal.application.LlmApikeyAdminService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/admin/llm/apikeys")
public class AdminLlmApikeyController {

    private final LlmApikeyAdminService apikeyAdminService;
    private final ChatApi chatApi;

    public AdminLlmApikeyController(LlmApikeyAdminService apikeyAdminService, ChatApi chatApi) {
        this.apikeyAdminService = apikeyAdminService;
        this.chatApi = chatApi;
    }

    @PostMapping
    public Mono<ApikeySnapshot> create(@Valid @RequestBody CreateApikeyRequest request) {
        return apikeyAdminService.create(
                request.name(),
                request.provider(),
                request.baseUrl(),
                request.secret(),
                request.enabledOrDefault(),
                request.expireTime());
    }

    @GetMapping
    public Mono<List<ApikeySnapshot>> list() {
        return apikeyAdminService.list();
    }

    @GetMapping("/{code}")
    public Mono<ApikeySnapshot> get(@PathVariable String code) {
        return apikeyAdminService.get(code);
    }

    @PutMapping("/{code}")
    public Mono<ApikeySnapshot> update(@PathVariable String code, @Valid @RequestBody UpdateApikeyRequest request) {
        return apikeyAdminService.update(
                code,
                request.getName(),
                request.getProvider(),
                request.getBaseUrl(),
                request.getSecret(),
                request.getEnabled(),
                request.getExpireTime(),
                request.expireTimePresent());
    }

    @GetMapping("/{code}/stats")
    public Mono<CallLogStats> stats(
            @PathVariable String code,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String model) {
        return apikeyAdminService.get(code).then(chatApi.stats(code, from, to, model));
    }

    public record CreateApikeyRequest(
            @NotBlank String name,
            @NotBlank String provider,
            @NotBlank String baseUrl,
            @NotBlank String secret,
            Boolean enabled,
            LocalDateTime expireTime) {
        boolean enabledOrDefault() {
            return enabled == null || enabled;
        }
    }

    public static final class UpdateApikeyRequest {

        private String name;
        private String provider;
        private String baseUrl;
        private String secret;
        private Boolean enabled;
        private LocalDateTime expireTime;
        private boolean expireTimePresent;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

        public LocalDateTime getExpireTime() {
            return expireTime;
        }

        @JsonSetter("expireTime")
        public void setExpireTime(LocalDateTime expireTime) {
            this.expireTime = expireTime;
            this.expireTimePresent = true;
        }

        boolean expireTimePresent() {
            return expireTimePresent;
        }
    }
}
