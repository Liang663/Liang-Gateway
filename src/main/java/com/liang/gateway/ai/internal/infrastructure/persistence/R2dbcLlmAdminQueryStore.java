package com.liang.gateway.ai.internal.infrastructure.persistence;

import com.liang.gateway.ai.internal.application.LlmAdminQueryStore;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public class R2dbcLlmAdminQueryStore implements LlmAdminQueryStore {
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final DatabaseClient database;

    public R2dbcLlmAdminQueryStore(DatabaseClient database) {
        this.database = database;
    }

    @Override
    public Mono<Summary> summary(LocalDateTime from, LocalDateTime to) {
        return interval("""
                SELECT COUNT(*) AS total, COALESCE(SUM(success = 1), 0) AS successes,
                       AVG(CASE WHEN first_token_ms >= 0 THEN first_token_ms END) AS average_ttft
                FROM llm_call_log WHERE create_time >= :from AND create_time < :to
                """, from, to)
                .map((row, metadata) -> new Summary(number(row.get("total")).longValue(),
                        number(row.get("successes")).longValue(),
                        row.get("average_ttft") == null ? null : number(row.get("average_ttft")).doubleValue()))
                .one();
    }

    @Override
    public Flux<Bucket> trend(LocalDateTime from, LocalDateTime to) {
        return interval("""
                SELECT DATE_FORMAT(create_time, '%Y-%m-%d %H:00:00') AS bucket, COUNT(*) AS total
                FROM llm_call_log WHERE create_time >= :from AND create_time < :to
                GROUP BY bucket ORDER BY bucket
                """, from, to)
                .map((row, metadata) -> new Bucket(LocalDateTime.parse(row.get("bucket", String.class), HOUR),
                        number(row.get("total")).longValue()))
                .all();
    }

    @Override
    public Flux<ModelCount> models(LocalDateTime from, LocalDateTime to) {
        return interval("""
                SELECT model, COUNT(*) AS total FROM llm_call_log
                WHERE create_time >= :from AND create_time < :to
                GROUP BY model ORDER BY total DESC, model
                """, from, to)
                .map((row, metadata) -> new ModelCount(row.get("model", String.class),
                        number(row.get("total")).longValue()))
                .all();
    }

    @Override
    public Mono<Long> count(Filter filter) {
        return filtered("SELECT COUNT(*) AS total FROM llm_call_log", filter, "")
                .map((row, metadata) -> number(row.get("total")).longValue()).one();
    }

    @Override
    public Flux<LogRow> logs(Filter filter, long offset, int limit) {
        return filtered("""
                SELECT code, llm_apikey_code, model, success, message, first_token_ms,
                       total_duration_ms, create_time FROM llm_call_log
                """, filter, " ORDER BY create_time DESC, id DESC LIMIT :limit OFFSET :offset")
                .bind("limit", limit).bind("offset", offset)
                .map((row, metadata) -> new LogRow(row.get("code", String.class),
                        row.get("llm_apikey_code", String.class), row.get("model", String.class),
                        isSuccessful(row.get("success")), row.get("message", String.class),
                        row.get("first_token_ms", Integer.class), number(row.get("total_duration_ms")).intValue(),
                        row.get("create_time", LocalDateTime.class)))
                .all();
    }

    private DatabaseClient.GenericExecuteSpec interval(String sql, LocalDateTime from, LocalDateTime to) {
        return database.sql(sql).bind("from", from).bind("to", to);
    }

    private DatabaseClient.GenericExecuteSpec filtered(String select, Filter filter, String suffix) {
        String sql = select + " WHERE create_time >= :from AND create_time < :to"
                + (filter.model() == null ? "" : " AND model = :model")
                + (filter.apikeyCode() == null ? "" : " AND llm_apikey_code = :apikey")
                + (filter.success() == null ? "" : " AND success = :success") + suffix;
        var query = interval(sql, filter.from(), filter.to());
        if (filter.model() != null) query = query.bind("model", filter.model());
        if (filter.apikeyCode() != null) query = query.bind("apikey", filter.apikeyCode());
        if (filter.success() != null) query = query.bind("success", filter.success() ? 1 : 0);
        return query;
    }

    private static Number number(Object value) {
        return (Number) value;
    }

    // MySQL drivers may expose TINYINT values as Boolean or a numeric type.
    static boolean isSuccessful(Object value) {
        return value instanceof Boolean success ? success : number(value).intValue() == 1;
    }
}
