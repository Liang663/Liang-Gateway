package com.liang.gateway.core.internal.infrastructure;

import io.netty.channel.ChannelOption;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Configuration
@EnableConfigurationProperties(GatewayProxyProperties.class)
public class WebClientConfig {

    @Bean(name = "gatewayWebClient")
    WebClient gatewayWebClient(GatewayProxyProperties properties) {
        Duration connectTimeout = properties.getConnectTimeout();
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(connectTimeout.toMillis()))
                .responseTimeout(properties.getResponseTimeout());
        int maxInMemorySize = Math.toIntExact(properties.getMaxInMemorySize().toBytes());
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(maxInMemorySize))
                .build();
    }
}
