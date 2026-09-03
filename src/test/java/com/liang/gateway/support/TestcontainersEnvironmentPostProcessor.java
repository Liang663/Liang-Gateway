package com.liang.gateway.support;

import com.redis.testcontainers.RedisContainer;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.testcontainers.mysql.MySQLContainer;

public class TestcontainersEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String PROPERTY_SOURCE_NAME = "testcontainers";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.getPropertySources().contains(PROPERTY_SOURCE_NAME)) {
            return;
        }
        SharedTestcontainers.start();
        MySQLContainer mysql = SharedTestcontainers.mysql();
        RedisContainer redis = SharedTestcontainers.redis();
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("spring.datasource.url", mysql.getJdbcUrl());
        properties.put("spring.datasource.username", mysql.getUsername());
        properties.put("spring.datasource.password", mysql.getPassword());
        properties.put("spring.datasource.driver-class-name", mysql.getDriverClassName());
        properties.put("spring.data.redis.host", redis.getRedisHost());
        properties.put("spring.data.redis.port", redis.getRedisPort());
        environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, properties));
    }
}
