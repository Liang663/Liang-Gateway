package com.liang.gateway.core.support;

import com.liang.gateway.core.ProxyApi;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration
public class TestProxyConfiguration {

    @Bean
    TestProxyController testProxyController(ProxyApi proxyApi) {
        return new TestProxyController(proxyApi);
    }
}
