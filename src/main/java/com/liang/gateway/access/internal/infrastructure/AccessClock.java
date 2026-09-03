package com.liang.gateway.access.internal.infrastructure;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

@Component
public class AccessClock {

    public static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter QPM_MINUTE = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private final Clock clock;

    public AccessClock(Clock clock) {
        this.clock = clock;
    }

    public Instant instant() {
        return clock.instant();
    }

    public long unixSeconds() {
        return instant().getEpochSecond();
    }

    public LocalDateTime nowShanghai() {
        return LocalDateTime.ofInstant(instant(), SHANGHAI);
    }

    public String qpmMinute() {
        return QPM_MINUTE.withZone(SHANGHAI).format(instant());
    }
}
