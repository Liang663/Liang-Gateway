package com.liang.gateway.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;

@SpringBootTest
class R2dbcMysqlProbeTest {

    @Autowired
    private DatabaseClient databaseClient;

    @Test
    @DisplayName("R2DBC 能引用 user 表并读写 TINYINT enabled")
    void readsQuotedUserTableAndTinyintEnabled() {
        String code = "probe-" + UUID.randomUUID();
        LocalDateTime now = LocalDateTime.of(2026, 1, 1, 0, 0, 0);
        databaseClient
                .sql(
                        """
                        INSERT INTO `user` (code, name, authority, enabled, create_time, update_time)
                        VALUES (:code, 'probe', 'DATA', 1, :now, :now)
                        """)
                .bind("code", code)
                .bind("now", now)
                .fetch()
                .rowsUpdated()
                .block(Duration.ofSeconds(5));
        try {
            Object enabled = databaseClient
                    .sql("SELECT enabled FROM `user` WHERE code = :code")
                    .bind("code", code)
                    .map((row, metadata) -> row.get("enabled"))
                    .one()
                    .block(Duration.ofSeconds(5));
            assertThat(enabled).isNotNull();
            boolean flag = toBoolean(enabled);
            assertThat(flag).isTrue();
        } finally {
            databaseClient
                    .sql("DELETE FROM `user` WHERE code = :code")
                    .bind("code", code)
                    .fetch()
                    .rowsUpdated()
                    .block(Duration.ofSeconds(5));
        }
    }

    private static boolean toBoolean(Object enabled) {
        if (enabled instanceof Boolean value) {
            return value;
        }
        if (enabled instanceof Byte value) {
            return value != 0;
        }
        if (enabled instanceof Number value) {
            return value.intValue() != 0;
        }
        throw new AssertionError("unexpected enabled type: " + enabled.getClass().getName() + " = " + enabled);
    }
}
