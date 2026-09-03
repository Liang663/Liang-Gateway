package com.liang.gateway.access.internal.infrastructure.redis;

import com.liang.gateway.access.QuotaLayer;
import com.liang.gateway.access.QuotaStoreUnavailableException;
import com.liang.gateway.access.internal.application.QuotaWindowStore;
import com.liang.gateway.access.internal.infrastructure.QuotaProperties;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class RedisQuotaWindowStore implements QuotaWindowStore {

    private static final long QPM_TTL_SECONDS = 120L;

    private final ReactiveStringRedisTemplate redis;
    private final QuotaKeys keys;
    private final RedisScript<List> refreshScript;
    private final RedisScript<List> incrScript;
    private final RedisScript<Long> qpmScript;

    public RedisQuotaWindowStore(ReactiveStringRedisTemplate redis, QuotaProperties properties) {
        this.redis = redis;
        this.keys = new QuotaKeys(properties.getKeyPrefix());
        this.refreshScript = loadListScript("access/redis/window_refresh.lua");
        this.incrScript = loadListScript("access/redis/window_incr.lua");
        this.qpmScript = loadLongScript("access/redis/qpm_incr.lua");
    }

    @Override
    public Mono<Void> openWindows(String tokenCode, Instant now) {
        String start = String.valueOf(now.getEpochSecond());
        Map<String, String> fields = Map.of("start", start, "used", "0");
        return Mono.zip(
                        redis.opsForHash().putAll(keys.window(tokenCode, QuotaLayer.FIVE_HOUR), fields),
                        redis.opsForHash().putAll(keys.window(tokenCode, QuotaLayer.WEEK), fields))
                .then()
                .onErrorMap(this::unavailable);
    }

    @Override
    public Mono<WindowSnapshot> refresh(String tokenCode, QuotaLayer layer, Instant now) {
        return executeList(
                refreshScript,
                keys.window(tokenCode, layer),
                String.valueOf(now.getEpochSecond()),
                String.valueOf(layer.durationSeconds()));
    }

    @Override
    public Mono<WindowSnapshot> refreshAndIncrement(String tokenCode, QuotaLayer layer, long tokens, Instant now) {
        return executeList(
                incrScript,
                keys.window(tokenCode, layer),
                String.valueOf(now.getEpochSecond()),
                String.valueOf(layer.durationSeconds()),
                String.valueOf(tokens));
    }

    @Override
    public Mono<WindowSnapshot> reset(String tokenCode, QuotaLayer layer, Instant now) {
        String start = String.valueOf(now.getEpochSecond());
        Map<String, String> fields = Map.of("start", start, "used", "0");
        String key = keys.window(tokenCode, layer);
        return redis.opsForHash()
                .putAll(key, fields)
                .thenReturn(new WindowSnapshot(now.getEpochSecond(), 0L))
                .onErrorMap(this::unavailable);
    }

    @Override
    public Mono<Long> incrementQpm(String tokenCode, String yyyyMMddHHmm) {
        return redis.execute(qpmScript, List.of(keys.qpm(tokenCode, yyyyMMddHHmm)), String.valueOf(QPM_TTL_SECONDS))
                .next()
                .switchIfEmpty(Mono.error(new QuotaStoreUnavailableException("Quota store unavailable")))
                .onErrorMap(this::unavailable);
    }

    private Mono<WindowSnapshot> executeList(RedisScript<List> script, String key, String... args) {
        return redis.execute(script, List.of(key), (Object[]) args)
                .next()
                .switchIfEmpty(Mono.error(new QuotaStoreUnavailableException("Quota store unavailable")))
                .map(this::toSnapshot)
                .onErrorMap(this::unavailable);
    }

    private WindowSnapshot toSnapshot(Object result) {
        if (!(result instanceof List<?> list) || list.size() < 2) {
            throw new QuotaStoreUnavailableException("Quota store unavailable");
        }
        return new WindowSnapshot(asLong(list.get(0)), asLong(list.get(1)));
    }

    private Throwable unavailable(Throwable error) {
        if (error instanceof QuotaStoreUnavailableException) {
            return error;
        }
        return new QuotaStoreUnavailableException("Quota store unavailable", error);
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof byte[] bytes) {
            return Long.parseLong(new String(bytes, StandardCharsets.UTF_8));
        }
        return Long.parseLong(String.valueOf(value));
    }

    @SuppressWarnings("rawtypes")
    private static RedisScript<List> loadListScript(String path) {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(List.class);
        return script;
    }

    private static RedisScript<Long> loadLongScript(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
    }
}
