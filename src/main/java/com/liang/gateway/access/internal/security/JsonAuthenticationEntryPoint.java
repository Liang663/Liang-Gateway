package com.liang.gateway.access.internal.security;

import com.liang.gateway.access.internal.web.ErrorEnvelope;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class JsonAuthenticationEntryPoint implements ServerAuthenticationEntryPoint {

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException ex) {
        return ErrorEnvelope.write(exchange, HttpStatus.UNAUTHORIZED, "unauthorized", "Unauthorized");
    }
}
