package com.liang.gateway.access.internal.web;

import com.liang.gateway.access.QuotaLayer;
import com.liang.gateway.access.internal.application.TokenAdminService;
import com.liang.gateway.access.internal.application.TokenSnapshot;
import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/admin/users/{userCode}/tokens")
public class AdminTokenController {

    private final TokenAdminService tokenAdminService;

    public AdminTokenController(TokenAdminService tokenAdminService) {
        this.tokenAdminService = tokenAdminService;
    }

    @PostMapping
    public Mono<TokenSnapshot> create(@PathVariable String userCode, @Valid @RequestBody CreateTokenRequest request) {
        return tokenAdminService.create(
                userCode,
                request.apikeyCode(),
                request.qpmLimit(),
                request.hourlyTokenLimit(),
                request.weeklyTokenLimit(),
                request.enabledOrDefault(),
                request.expireTime());
    }

    @GetMapping
    public Mono<List<TokenSnapshot>> list(@PathVariable String userCode) {
        return tokenAdminService.list(userCode);
    }

    @GetMapping("/{tokenCode}")
    public Mono<TokenSnapshot> get(@PathVariable String userCode, @PathVariable String tokenCode) {
        return tokenAdminService.get(userCode, tokenCode);
    }

    @PutMapping("/{tokenCode}")
    public Mono<TokenSnapshot> update(
            @PathVariable String userCode,
            @PathVariable String tokenCode,
            @Valid @RequestBody UpdateTokenRequest request) {
        return tokenAdminService.update(
                userCode,
                tokenCode,
                request.getEnabled(),
                request.getExpireTime(),
                request.expireTimePresent(),
                request.getQpmLimit(),
                request.getHourlyTokenLimit(),
                request.getWeeklyTokenLimit());
    }

    @PostMapping("/{tokenCode}/quota/reset")
    public Mono<Void> resetQuota(
            @PathVariable String userCode,
            @PathVariable String tokenCode,
            @Valid @RequestBody ResetQuotaRequest request) {
        return tokenAdminService.resetQuota(userCode, tokenCode, request.layer());
    }

    public record CreateTokenRequest(
            @NotBlank String apikeyCode,
            @NotNull Integer qpmLimit,
            @NotNull Long hourlyTokenLimit,
            @NotNull Long weeklyTokenLimit,
            Boolean enabled,
            LocalDateTime expireTime) {
        boolean enabledOrDefault() {
            return enabled == null || enabled;
        }
    }

    public static final class UpdateTokenRequest {

        @NotNull
        private Integer qpmLimit;

        @NotNull
        private Long hourlyTokenLimit;

        @NotNull
        private Long weeklyTokenLimit;

        private Boolean enabled;
        private LocalDateTime expireTime;
        private boolean expireTimePresent;

        public Integer getQpmLimit() {
            return qpmLimit;
        }

        public void setQpmLimit(Integer qpmLimit) {
            this.qpmLimit = qpmLimit;
        }

        public Long getHourlyTokenLimit() {
            return hourlyTokenLimit;
        }

        public void setHourlyTokenLimit(Long hourlyTokenLimit) {
            this.hourlyTokenLimit = hourlyTokenLimit;
        }

        public Long getWeeklyTokenLimit() {
            return weeklyTokenLimit;
        }

        public void setWeeklyTokenLimit(Long weeklyTokenLimit) {
            this.weeklyTokenLimit = weeklyTokenLimit;
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

    public record ResetQuotaRequest(@NotNull QuotaLayer layer) {}
}
