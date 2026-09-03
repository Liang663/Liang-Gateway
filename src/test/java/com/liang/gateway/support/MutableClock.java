package com.liang.gateway.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class MutableClock extends Clock {

    private final AtomicReference<Clock> delegate = new AtomicReference<>(Clock.systemUTC());

    public void setInstant(Instant instant) {
        delegate.set(Clock.fixed(instant, ZoneOffset.UTC));
    }

    public void reset() {
        delegate.set(Clock.systemUTC());
    }

    @Override
    public ZoneId getZone() {
        return delegate.get().getZone();
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return delegate.get().withZone(zone);
    }

    @Override
    public Instant instant() {
        return delegate.get().instant();
    }
}
