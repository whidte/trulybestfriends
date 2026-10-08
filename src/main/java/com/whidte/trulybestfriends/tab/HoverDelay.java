package com.whidte.trulybestfriends.tab;

import net.minecraft.Util;

import java.util.Objects;

/** 跟踪同一悬停目标是否已持续足够长时间以显示悬浮提示。 */
final class HoverDelay {
    private static final long DEFAULT_DELAY_MILLIS = 1000L;

    private Object target;
    private long startMillis = -1L;

    boolean isReady(Object currentTarget) {
        if (currentTarget == null) {
            target = null;
            startMillis = -1L;
            return false;
        }

        long now = Util.getMillis();
        if (!Objects.equals(target, currentTarget)) {
            target = currentTarget;
            startMillis = now;
        }
        return now - startMillis >= DEFAULT_DELAY_MILLIS;
    }
}
