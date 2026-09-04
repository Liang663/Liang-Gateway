package com.liang.gateway.core.support;

import com.liang.gateway.core.ProxyApi;
import com.liang.gateway.core.Upstream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
public class TestProxyController {

    private static final String EXTRA_PREFIX = "x-upstream-extra-";

    private final ProxyApi proxyApi;

    public TestProxyController(ProxyApi proxyApi) {
        this.proxyApi = proxyApi;
    }

    @RequestMapping("/__test__/proxy/forward")
    public Mono<Void> forward(ServerWebExchange exchange) {
        HttpHeaders headers = exchange.getRequest().getHeaders();
        String url = headers.getFirst("X-Upstream-Url");
        boolean stream = "true".equalsIgnoreCase(headers.getFirst("X-Upstream-Stream"));
        boolean drain = "true".equalsIgnoreCase(headers.getFirst("X-Drain-After-Cancel"));
        String method = headers.getFirst("X-Upstream-Method");
        String overrideBody = headers.getFirst("X-Upstream-Override-Body");
        String timeoutMs = headers.getFirst("X-Upstream-Timeout-Ms");
        byte[] body = overrideBody == null ? null : overrideBody.getBytes(StandardCharsets.UTF_8);
        Duration timeout = timeoutMs == null || timeoutMs.isBlank()
                ? null
                : Duration.ofMillis(Long.parseLong(timeoutMs));
        Map<String, String> extra = extraHeaders(headers);
        Upstream upstream = new Upstream(URI.create(url), extra, stream, method, body, timeout);
        return proxyApi.forward(exchange, upstream, null, drain);
    }

    private static Map<String, String> extraHeaders(HttpHeaders headers) {
        Map<String, String> extra = new LinkedHashMap<>();
        headers.forEach((name, values) -> {
            if (name != null
                    && values != null
                    && !values.isEmpty()
                    && name.toLowerCase(Locale.ROOT).startsWith(EXTRA_PREFIX)) {
                extra.put(name.substring(EXTRA_PREFIX.length()), values.getFirst());
            }
        });
        return extra;
    }
}
