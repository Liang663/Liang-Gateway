package com.liang.gateway.access.internal.security;

import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class GatewayAuthenticationConverter implements ServerAuthenticationConverter {

    static final String ADMIN_TOKEN_HEADER = "X-Admin-Token";
    static final String API_KEY_HEADER = "api-key";
    static final String API_KEY_QUERY = "api_key";

    @Override
    public Mono<Authentication> convert(ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        if ("/health".equals(path) || "/console".equals(path) || path.startsWith("/console/")) {
            return Mono.empty();
        }
        if (path.startsWith("/admin")) {
            String adminToken = exchange.getRequest().getHeaders().getFirst(ADMIN_TOKEN_HEADER);
            if (adminToken == null || adminToken.isBlank()) {
                return Mono.empty();
            }
            return Mono.just(AdminAuthenticationToken.unauthenticated(adminToken));
        }
        String accessToken = extractAccessToken(exchange);
        if (accessToken == null || accessToken.isBlank()) {
            return Mono.empty();
        }
        return Mono.just(AccessAuthenticationToken.unauthenticated(accessToken));
    }

    private static String extractAccessToken(ServerWebExchange exchange) {
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return authorization.substring(7).trim();
        }
        String headerKey = exchange.getRequest().getHeaders().getFirst(API_KEY_HEADER);
        if (headerKey != null && !headerKey.isBlank()) {
            return headerKey.trim();
        }
        String queryKey = exchange.getRequest().getQueryParams().getFirst(API_KEY_QUERY);
        return queryKey == null ? null : queryKey.trim();
    }
}
