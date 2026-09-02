package com.liang.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.util.ClassUtils;

class ApplicationModulesTest {

    @Test
    @DisplayName("四模块依赖方向正确且未引入 Servlet")
    void verifiesModuleStructure() {
        ApplicationModules modules = ApplicationModules.of(GatewayApplication.class);
        modules.verify();

        assertThat(modules.stream().map(module -> module.getIdentifier().toString()))
                .containsExactlyInAnyOrder("core", "access", "ai", "orchestration");
        assertThat(ClassUtils.isPresent("org.springframework.web.servlet.DispatcherServlet", null)).isFalse();
    }
}
