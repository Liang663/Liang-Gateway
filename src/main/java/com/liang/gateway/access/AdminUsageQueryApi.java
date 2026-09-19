package com.liang.gateway.access;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import reactor.core.publisher.Mono;

/** Read-only queries used by the administrator console. Amounts are integer fen. */
public interface AdminUsageQueryApi {

    Mono<Stats> stats(String range, String userCode, String tokenCode, String model);

    Mono<Page> records(RecordQuery query);

    Mono<QuotaView> quota(String userCode, String tokenCode);

    record RecordQuery(int page, int pageSize, OffsetDateTime from, OffsetDateTime to,
            String userCode, String tokenCode, String model) {}

    record Stats(long totalRecords, long promptTokens, long completionTokens, long totalTokens, long amountFen) {}

    record Record(String code, String userCode, String tokenCode, long promptTokens,
            long completionTokens, long totalTokens, long amountFen, String model,
            String requestId, LocalDateTime createTime) {}

    record Page(List<Record> items, long total, int page, int pageSize) {}
}
