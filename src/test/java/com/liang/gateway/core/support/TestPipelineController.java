package com.liang.gateway.core.support;

import com.liang.gateway.core.GatewayExchange;
import com.liang.gateway.core.PipelineApi;
import com.liang.gateway.core.Upstream;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
public class TestPipelineController {

    private static final String EXTRA_PREFIX = "x-upstream-extra-";

    private final PipelineApi pipelineApi;

    public TestPipelineController(PipelineApi pipelineApi) {
        this.pipelineApi = pipelineApi;
    }

    @RequestMapping("/__test__/pipeline")
    public Mono<Void> pipeline(ServerWebExchange exchange) {
        applyUpstreamFromHeaders(exchange);
        return pipelineApi.execute(exchange);
    }

    private static void applyUpstreamFromHeaders(ServerWebExchange exchange) {
        HttpHeaders headers = exchange.getRequest().getHeaders();
        String url = headers.getFirst("X-Upstream-Url");
        if (url == null || url.isBlank()) {
            return;
        }
        boolean stream = "true".equalsIgnoreCase(headers.getFirst("X-Upstream-Stream"));
        Map<String, String> extra = new LinkedHashMap<>();
        headers.forEach((name, values) -> {
            if (name != null && values != null && !values.isEmpty()
                    && name.toLowerCase(Locale.ROOT).startsWith(EXTRA_PREFIX)) {
                extra.put(name.substring(EXTRA_PREFIX.length()), values.getFirst());
            }
        });
        exchange.getAttributes()
                .put(GatewayExchange.UPSTREAM_ATTRIBUTE, new Upstream(URI.create(url), extra, stream));
    }
}
