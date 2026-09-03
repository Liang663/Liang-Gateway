package com.liang.gateway.access.internal.infrastructure;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(QuotaProperties.class)
public class AccessInfrastructureConfig {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock systemUtcClock() {
        return Clock.systemUTC();
    }
}
