package com.liang.gateway.orchestration;

import com.liang.gateway.access.internal.application.TokenAdminService;
import com.liang.gateway.access.internal.application.TokenSnapshot;
import com.liang.gateway.access.internal.application.UsageLimitInput;
import com.liang.gateway.access.internal.application.UserAdminService;
import com.liang.gateway.access.internal.application.UserSnapshot;
import com.liang.gateway.ai.internal.application.ApikeySnapshot;
import com.liang.gateway.ai.internal.application.LlmApikeyAdminService;
import com.liang.gateway.ai.internal.application.LlmModelAdminService;
import com.liang.gateway.ai.internal.application.ModelSnapshot;
import java.util.ArrayList;
import java.util.List;

final class ChatTestSupport {

    private ChatTestSupport() {}

    static Catalog seed(
            UserAdminService userAdminService,
            TokenAdminService tokenAdminService,
            LlmApikeyAdminService apikeyAdminService,
            LlmModelAdminService modelAdminService,
            String baseUrl,
            List<String> extraAllowedModels,
            boolean authorizeCatalogModel,
            List<UsageLimitInput> limits,
            int qpm) {
        String modelName = "chat-" + System.nanoTime();
        String secret = "sk-chat-" + System.nanoTime();
        UserSnapshot user = userAdminService.create("chat-user-" + System.nanoTime(), "DATA", true).block();
        ApikeySnapshot key = apikeyAdminService
                .create("chat-key-" + System.nanoTime(), "deepseek", baseUrl, secret, true, null)
                .block();
        ModelSnapshot model = modelAdminService
                .create(modelName, "deepseek", key.code(), 200L, 400L, true)
                .block();
        List<String> allowed = new ArrayList<>(extraAllowedModels);
        if (authorizeCatalogModel) {
            allowed.add(modelName);
        }
        if (allowed.isEmpty()) {
            allowed.add("placeholder-unauthorized");
        }
        TokenSnapshot token = tokenAdminService
                .create(user.code(), qpm, true, null, allowed, limits)
                .block();
        return new Catalog(user.code(), token.code(), token.accessToken(), model.name(), key.code(), secret);
    }

    static List<UsageLimitInput> generousLimits() {
        return List.of(new UsageLimitInput(1, 10_000L), new UsageLimitInput(2, 10_000L));
    }

    record Catalog(
            String userCode,
            String tokenCode,
            String accessToken,
            String modelName,
            String apikeyCode,
            String secret) {}
}
