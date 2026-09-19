package com.liang.gateway.core.internal.infrastructure;

import io.netty.channel.ChannelOption;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

@Configuration
@EnableConfigurationProperties(GatewayProxyProperties.class)
public class WebClientConfig {

    @Bean(destroyMethod = "dispose")
    ConnectionProvider gatewayConnectionProvider(GatewayProxyProperties properties) {
        int maxConnections = Math.max(1, properties.getMaxConnections());
        return ConnectionProvider.builder("gateway-proxy")
                .maxConnections(maxConnections)
                .pendingAcquireMaxCount(maxConnections * 2)
                .pendingAcquireTimeout(Duration.ofSeconds(60))
                .maxIdleTime(Duration.ofSeconds(60))
                .build();
    }

    @Bean(name = "gatewayWebClient")
    WebClient gatewayWebClient(GatewayProxyProperties properties, ConnectionProvider gatewayConnectionProvider) {
        Duration connectTimeout = properties.getConnectTimeout();
        HttpClient httpClient = HttpClient.create(gatewayConnectionProvider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(connectTimeout.toMillis()))
                .responseTimeout(properties.getResponseTimeout());
        int maxInMemorySize = Math.toIntExact(properties.getMaxInMemorySize().toBytes());
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(maxInMemorySize))
                .build();
    }
}
