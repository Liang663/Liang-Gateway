package com.liang.gateway.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.liang.gateway.access.internal.application.AccessBadRequestException;
import com.liang.gateway.access.internal.application.AccessNotFoundException;
import com.liang.gateway.access.internal.application.AdminUsageQueryService;
import com.liang.gateway.access.internal.application.AdminUsageQueryStore;
import com.liang.gateway.access.internal.application.TokenAdminService;
import com.liang.gateway.access.internal.application.TokenSnapshot;
import com.liang.gateway.access.internal.infrastructure.AccessClock;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AdminUsageQueryServiceTest {

    private final AdminUsageQueryStore store = mock(AdminUsageQueryStore.class);
    private final TokenAdminService tokens = mock(TokenAdminService.class);
    private final AccessApi access = mock(AccessApi.class);
    private final AdminUsageQueryService service = new AdminUsageQueryService(store,
            new AccessClock(Clock.fixed(Instant.parse("2026-09-18T04:00:00Z"), ZoneOffset.UTC)), tokens, access);

    @Test
    @DisplayName("统计默认最近24小时且筛选空白值省略")
    void defaultStatsUsesShanghaiClock() {
        var expected = new AdminUsageQueryStore.Filter(LocalDateTime.parse("2026-09-17T12:00:00"),
                LocalDateTime.parse("2026-09-18T12:00:00"), "user", null, "model");
        var stats = new AdminUsageQueryApi.Stats(0, 0, 0, 0, 0);
        when(store.aggregate(expected)).thenReturn(Mono.just(stats));
        StepVerifier.create(service.stats(null, " user ", " ", "model")).expectNext(stats).verifyComplete();
        verify(store).aggregate(expected);
    }

    @Test
    @DisplayName("错误区间与分页在查询数据库前拒绝")
    void rejectsInvalidQueriesBeforeDatabase() {
        StepVerifier.create(service.stats("month", null, null, null)).expectError(AccessBadRequestException.class).verify();
        for (var query : List.of(
                new AdminUsageQueryApi.RecordQuery(0, 20, null, null, null, null, null),
                new AdminUsageQueryApi.RecordQuery(1, 0, null, null, null, null, null),
                new AdminUsageQueryApi.RecordQuery(1, 20, OffsetDateTime.parse("2026-09-18T04:00:00Z"),
                        OffsetDateTime.parse("2026-09-18T04:00:00Z"), null, null, null))) {
            StepVerifier.create(service.records(query)).expectError(AccessBadRequestException.class).verify();
        }
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("分页偏移以long计算且单页大小不超过100")
    void capsPageSizeAndAvoidsOffsetOverflow() {
        when(store.records(any(), anyLong(), anyInt())).thenReturn(Flux.empty());
        when(store.count(any())).thenReturn(Mono.just(3L));
        var query = new AdminUsageQueryApi.RecordQuery(Integer.MAX_VALUE, 200, null, null, null, null, null);
        StepVerifier.create(service.records(query)).assertNext(page -> {
            assertThat(page.items()).isEmpty();
            assertThat(page.total()).isEqualTo(3);
            assertThat(page.pageSize()).isEqualTo(100);
            assertThat(page.page()).isEqualTo(Integer.MAX_VALUE);
        }).verifyComplete();
        verify(store).records(new AdminUsageQueryStore.Filter(LocalDateTime.parse("2026-09-17T12:00:00"),
                LocalDateTime.parse("2026-09-18T12:00:00"), null, null, null), 214748364600L, 100);
    }

    @Test
    @DisplayName("带偏移量的查询区间转换为上海本地时间")
    void convertsOffsetsToShanghai() {
        var filter = new AdminUsageQueryStore.Filter(LocalDateTime.parse("2026-09-17T12:00:00"),
                LocalDateTime.parse("2026-09-18T12:00:00"), "user", "token", "model");
        when(store.records(filter, 0, 20)).thenReturn(Flux.empty());
        when(store.count(filter)).thenReturn(Mono.just(0L));
        var query = new AdminUsageQueryApi.RecordQuery(1, 20, OffsetDateTime.parse("2026-09-17T04:00:00Z"),
                OffsetDateTime.parse("2026-09-18T12:00:00+08:00"), "user", "token", "model");
        StepVerifier.create(service.records(query)).expectNext(new AdminUsageQueryApi.Page(List.of(), 0, 1, 20))
                .verifyComplete();
        verify(store).count(filter);
    }

    @Test
    @DisplayName("额度查询在归属校验失败时不读取Redis")
    void rejectsUnownedTokenBeforeQuotaRead() {
        when(tokens.get("user", "token")).thenReturn(Mono.error(new AccessNotFoundException("Access token not found")));
        StepVerifier.create(service.quota("user", "token")).expectError(AccessNotFoundException.class).verify();
        verifyNoInteractions(access);
    }

    @Test
    @DisplayName("归属校验成功后使用公开AccessApi读取额度")
    void readsOwnedTokenQuota() {
        var token = new TokenSnapshot("token", "user", "unused", true, null, 60, List.of(), List.of(), null, null);
        var quota = new QuotaView(new WindowView(3, 10, null, null), new WindowView(3, 100, null, null));
        when(tokens.get("user", "token")).thenReturn(Mono.just(token));
        when(access.getQuota("token")).thenReturn(Mono.just(quota));
        StepVerifier.create(service.quota("user", "token")).expectNext(quota).verifyComplete();
    }
}
