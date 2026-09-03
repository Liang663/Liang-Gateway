package com.liang.gateway.access.internal.web;

import com.liang.gateway.access.QuotaLayer;
import com.liang.gateway.access.internal.application.AccessBadRequestException;
import com.liang.gateway.access.internal.application.TokenAdminService;
import com.liang.gateway.access.internal.application.TokenSnapshot;
import com.liang.gateway.access.internal.application.UsageLimitInput;
import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
                request.qpmLimit(),
                request.enabledOrDefault(),
                request.expireTime(),
                request.models() == null ? List.of() : request.models(),
                toInputs(request.limits()));
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
                request.modelsPresent() ? request.getModels() : null,
                request.limitsPresent() ? toInputs(request.getLimits()) : null);
    }

    @PostMapping("/{tokenCode}/quota/reset")
    public Mono<Void> resetQuota(
            @PathVariable String userCode,
            @PathVariable String tokenCode,
            @Valid @RequestBody ResetQuotaRequest request) {
        return tokenAdminService.resetQuota(userCode, tokenCode, request.layer());
    }

    private static List<UsageLimitInput> toInputs(List<LimitBody> limits) {
        if (limits == null || limits.isEmpty()) {
            return List.of();
        }
        List<UsageLimitInput> inputs = new ArrayList<>();
        for (LimitBody body : limits) {
            if (body == null || body.limitType() == null || body.usage() == null) {
                throw new AccessBadRequestException("limits require limitType and usage");
            }
            inputs.add(new UsageLimitInput(body.limitType(), body.usage()));
        }
        return inputs;
    }

    public record CreateTokenRequest(
            @NotNull Integer qpmLimit,
            List<String> models,
            @Valid List<@Valid LimitBody> limits,
            Boolean enabled,
            LocalDateTime expireTime) {
        boolean enabledOrDefault() {
            return enabled == null || enabled;
        }
    }

    public record LimitBody(@NotNull Integer limitType, @NotNull Long usage) {}

    public static final class UpdateTokenRequest {

        @NotNull
        private Integer qpmLimit;

        private Boolean enabled;
        private LocalDateTime expireTime;
        private boolean expireTimePresent;
        private List<String> models;
        private boolean modelsPresent;
        @Valid
        private List<LimitBody> limits;
        private boolean limitsPresent;

        public Integer getQpmLimit() {
            return qpmLimit;
        }

        public void setQpmLimit(Integer qpmLimit) {
            this.qpmLimit = qpmLimit;
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

        public List<String> getModels() {
            return models;
        }

        @JsonSetter("models")
        public void setModels(List<String> models) {
            this.models = models;
            this.modelsPresent = true;
        }

        boolean modelsPresent() {
            return modelsPresent;
        }

        public List<LimitBody> getLimits() {
            return limits;
        }

        @JsonSetter("limits")
        public void setLimits(List<LimitBody> limits) {
            this.limits = limits;
            this.limitsPresent = true;
        }

        boolean limitsPresent() {
            return limitsPresent;
        }
    }

    public record ResetQuotaRequest(@NotNull QuotaLayer layer) {}
}
