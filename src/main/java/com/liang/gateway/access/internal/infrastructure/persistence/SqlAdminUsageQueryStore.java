package com.liang.gateway.access.internal.infrastructure.persistence;

import com.liang.gateway.access.AdminUsageQueryApi;
import com.liang.gateway.access.internal.application.AdminUsageQueryStore;
import io.r2dbc.spi.Row;
import java.time.LocalDateTime;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public class SqlAdminUsageQueryStore implements AdminUsageQueryStore {

    private final DatabaseClient database;

    public SqlAdminUsageQueryStore(DatabaseClient database) {
        this.database = database;
    }

    @Override
    public Mono<AdminUsageQueryApi.Stats> aggregate(Filter filter) {
        String sql = """
                SELECT COUNT(*) AS total_records,
                       COALESCE(SUM(prompt_tokens), 0) AS prompt_tokens,
                       COALESCE(SUM(completion_tokens), 0) AS completion_tokens,
                       COALESCE(SUM(total_tokens), 0) AS total_tokens,
                       COALESCE(SUM(amount_fen), 0) AS amount_fen
                FROM usage_record
                """ + where(filter);
        return bind(database.sql(sql), filter).map((row, metadata) -> new AdminUsageQueryApi.Stats(
                number(row, "total_records"), number(row, "prompt_tokens"), number(row, "completion_tokens"),
                number(row, "total_tokens"), number(row, "amount_fen"))).one();
    }

    @Override
    public Mono<Long> count(Filter filter) {
        return bind(database.sql("SELECT COUNT(*) AS total FROM usage_record " + where(filter)), filter)
                .map((row, metadata) -> number(row, "total")).one();
    }

    @Override
    public Flux<AdminUsageQueryApi.Record> records(Filter filter, long offset, int limit) {
        String sql = """
                SELECT code, user_code, token_code, prompt_tokens, completion_tokens,
                       total_tokens, amount_fen, model, request_id, create_time
                FROM usage_record
                """ + where(filter) + " ORDER BY create_time DESC, id DESC LIMIT :limit OFFSET :offset";
        return bind(database.sql(sql), filter).bind("limit", limit).bind("offset", offset)
                .map((row, metadata) -> new AdminUsageQueryApi.Record(
                        row.get("code", String.class), row.get("user_code", String.class),
                        row.get("token_code", String.class), number(row, "prompt_tokens"),
                        number(row, "completion_tokens"), number(row, "total_tokens"),
                        number(row, "amount_fen"), row.get("model", String.class),
                        row.get("request_id", String.class), row.get("create_time", LocalDateTime.class)))
                .all();
    }

    private static String where(Filter filter) {
        return " WHERE create_time >= :fromTime AND create_time < :toTime"
                + (filter.userCode() == null ? "" : " AND user_code = :userCode")
                + (filter.tokenCode() == null ? "" : " AND token_code = :tokenCode")
                + (filter.model() == null ? "" : " AND model = :model");
    }

    private static DatabaseClient.GenericExecuteSpec bind(DatabaseClient.GenericExecuteSpec query, Filter filter) {
        query = query.bind("fromTime", filter.from()).bind("toTime", filter.to());
        if (filter.userCode() != null) query = query.bind("userCode", filter.userCode());
        if (filter.tokenCode() != null) query = query.bind("tokenCode", filter.tokenCode());
        if (filter.model() != null) query = query.bind("model", filter.model());
        return query;
    }

    private static long number(Row row, String column) {
        Number value = (Number) row.get(column);
        return value == null ? 0 : value.longValue();
    }
}
