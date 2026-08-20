package com.whidte.trulybestfriends.tab;

import net.minecraft.Util;

import java.util.Objects;

/** Tracks whether the same hover target has remained active long enough to show a tooltip. */
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
