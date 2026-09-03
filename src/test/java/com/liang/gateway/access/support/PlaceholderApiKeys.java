package com.liang.gateway.access.support;

import org.springframework.jdbc.core.JdbcTemplate;

public final class PlaceholderApiKeys {

    private PlaceholderApiKeys() {}

    public static void insert(JdbcTemplate jdbcTemplate, String code) {
        jdbcTemplate.update(
                """
                INSERT INTO llm_apikey_config
                (code, name, provider, base_url, secret, prefix, enabled, expire_time, create_time, update_time)
                VALUES (?, 'placeholder', 'deepseek', 'http://127.0.0.1', 'sk-test-placeholder', 'sk-test', 1, NULL, NOW(3), NOW(3))
                """,
                code);
    }
}
