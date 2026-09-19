package com.liang.gateway.access;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.liang.gateway.access.internal.security.AccessSecurityConfig;
import com.liang.gateway.access.internal.security.GatewayAuthenticationConverter;
import com.liang.gateway.access.internal.security.GatewayReactiveAuthenticationManager;
import com.liang.gateway.access.internal.security.JsonAuthenticationEntryPoint;
import com.liang.gateway.core.internal.web.ConsoleController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.reactive.config.ResourceHandlerRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import reactor.core.publisher.Mono;

/** Isolated security regression tests; no database, Redis or frontend build is needed. */
class ConsoleSecurityTest {

    private AnnotationConfigApplicationContext context;
    private WebTestClient client;
    private GatewayReactiveAuthenticationManager authenticationManager;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext(TestConfiguration.class);
        authenticationManager = context.getBean(GatewayReactiveAuthenticationManager.class);
        client = WebTestClient.bindToApplicationContext(context).build();
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void entryPointsRedirectWithoutCredentials() {
        for (String path : new String[] {"/console", "/console/"}) {
            client.get().uri(path).exchange().expectStatus().isFound()
                    .expectHeader().location("/console/index.html");
        }
        verifyNoInteractions(authenticationManager);
    }

    @Test
    void staticAssetsArePublicEvenWithInvalidCredentials() {
        client.get().uri("/console/console-security-test.js?api_key=invalid")
                .header(HttpHeaders.AUTHORIZATION, "Bearer invalid")
                .header("api-key", "invalid")
                .header("X-Admin-Token", "invalid")
                .exchange().expectStatus().isOk()
                .expectBody(String.class).isEqualTo("window.consoleSecurityFixture = true;\n");
        verifyNoInteractions(authenticationManager);
    }

    @Test
    void unrelatedPathsAndAdminQueriesRemainProtected() {
        for (String path : new String[] {"/console-private", "/admin/users", "/admin/llm/stats", "/v1/usage/quota"}) {
            client.get().uri(path).exchange().expectStatus().isUnauthorized();
        }
    }

    @Test
    void invalidAdminCredentialsAreRejected() {
        client.get().uri("/admin/users").header("X-Admin-Token", "invalid")
                .exchange().expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.error.type").isEqualTo("unauthorized");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebFlux
    @Import({AccessSecurityConfig.class, GatewayAuthenticationConverter.class,
            JsonAuthenticationEntryPoint.class, ConsoleController.class})
    static class TestConfiguration implements WebFluxConfigurer {

        @Bean
        GatewayReactiveAuthenticationManager authenticationManager() {
            GatewayReactiveAuthenticationManager manager = mock(GatewayReactiveAuthenticationManager.class);
            when(manager.authenticate(any())).thenReturn(Mono.error(new BadCredentialsException("unauthorized")));
            return manager;
        }

        @Override
        public void addResourceHandlers(ResourceHandlerRegistry registry) {
            registry.addResourceHandler("/console/**").addResourceLocations("classpath:/static/console/");
        }
    }
}
