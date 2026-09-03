package com.liang.gateway.ai.internal.infrastructure;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

@Component
public class AiClock {

    public static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final Clock clock;

    public AiClock(Clock clock) {
        this.clock = clock;
    }

    public Instant instant() {
        return clock.instant();
    }

    public LocalDateTime nowShanghai() {
        return LocalDateTime.ofInstant(instant(), SHANGHAI);
    }
}
