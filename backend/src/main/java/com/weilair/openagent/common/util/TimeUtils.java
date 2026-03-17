package com.weilair.openagent.common.util;

import java.time.LocalDateTime;
import java.time.ZoneId;

public final class TimeUtils {

    private static final ZoneId DEFAULT_ZONE = ZoneId.systemDefault();

    private TimeUtils() {
    }

    public static Long toEpochMillis(LocalDateTime value) {
        if (value == null) {
            return null;
        }
        return value.atZone(DEFAULT_ZONE).toInstant().toEpochMilli();
    }
}
