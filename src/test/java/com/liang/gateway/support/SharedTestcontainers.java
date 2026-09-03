package com.liang.gateway.support;

import com.redis.testcontainers.RedisContainer;
import org.testcontainers.mysql.MySQLContainer;

public final class SharedTestcontainers {

    private static final Object LOCK = new Object();
    private static MySQLContainer mysql;
    private static RedisContainer redis;

    private SharedTestcontainers() {}

    public static void start() {
        synchronized (LOCK) {
            if (mysql != null) {
                return;
            }
            WindowsPathSanitizer.stripQuotedPathEntries();
            mysql = new MySQLContainer("mysql:8.0")
                    .withDatabaseName("liang_gateway_test")
                    .withUsername("test")
                    .withPassword("test")
                    .withUrlParam("useUnicode", "true")
                    .withUrlParam("characterEncoding", "utf8")
                    .withUrlParam("serverTimezone", "Asia/Shanghai");
            redis = new RedisContainer("redis:7-alpine");
            mysql.start();
            redis.start();
        }
    }

    public static MySQLContainer mysql() {
        start();
        return mysql;
    }

    public static RedisContainer redis() {
        start();
        return redis;
    }
}
