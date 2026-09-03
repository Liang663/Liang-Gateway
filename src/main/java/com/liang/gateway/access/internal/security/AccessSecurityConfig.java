package com.liang.gateway.access.internal.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;
import org.springframework.security.web.server.authentication.ServerAuthenticationEntryPointFailureHandler;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;

@Configuration
@EnableWebFluxSecurity
public class AccessSecurityConfig {

    @Bean
    SecurityWebFilterChain accessSecurityWebFilterChain(
            ServerHttpSecurity http,
            GatewayAuthenticationConverter converter,
            GatewayReactiveAuthenticationManager authenticationManager,
            JsonAuthenticationEntryPoint entryPoint) {
        AuthenticationWebFilter authenticationWebFilter = new AuthenticationWebFilter(authenticationManager);
        authenticationWebFilter.setServerAuthenticationConverter(converter);
        authenticationWebFilter.setAuthenticationFailureHandler(
                new ServerAuthenticationEntryPointFailureHandler(entryPoint));
        authenticationWebFilter.setSecurityContextRepository(NoOpServerSecurityContextRepository.getInstance());

        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .requestCache(ServerHttpSecurity.RequestCacheSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .exceptionHandling(spec -> spec.authenticationEntryPoint(entryPoint))
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/health")
                        .permitAll()
                        .pathMatchers("/admin/**")
                        .authenticated()
                        .anyExchange()
                        .authenticated())
                .addFilterAt(authenticationWebFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }
}
