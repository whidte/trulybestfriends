package com.whidte.trulybestfriends.network;

/**
 * 存在性探测三态判定的独立冒烟测试。
 * 只覆盖纯函数 {@link PetPresenceProbe#classify}，不触碰任何世界状态。
 */
public final class PetPresenceProbeSmokeTest {
    private PetPresenceProbeSmokeTest() {}

    public static void main(String[] args) {
        int checks = 0;
        int confirm = PetPresenceProbe.MIN_CONFIRM_TICKS;
        int max = PetPresenceProbe.MAX_PROBE_TICKS;

        require(PetPresenceProbe.MIN_CONFIRM_TICKS < PetPresenceProbe.MAX_PROBE_TICKS,
                "the confirmation window must be shorter than the give-up timeout");
        checks++;

        // -- 已加载：无论区块与计时如何，都是在世界上 --
        require(PetPresenceProbe.classify(true, true, 0) == PetPresenceProbe.Presence.IN_WORLD,
                "a found entity was not reported as in world");
        require(PetPresenceProbe.classify(true, false, max * 2) == PetPresenceProbe.Presence.IN_WORLD,
                "a found entity was not reported as in world when its chunk was unloaded");
        checks += 2;

        // -- 区块未加载：状态未知，与「不存在」不可区分 --
        require(PetPresenceProbe.classify(false, false, 0) == PetPresenceProbe.Presence.UNLOADED,
                "an unloaded chunk was not reported as unknown");
        require(PetPresenceProbe.classify(false, false, max * 2) == PetPresenceProbe.Presence.UNLOADED,
                "waiting longer must not turn an unloaded chunk into a missing pet");
        checks += 2;

        // -- 区块已加载却找不到：先等满确认窗口，之后才敢认定确证不在 --
        require(PetPresenceProbe.classify(false, true, 0) == PetPresenceProbe.Presence.PENDING,
                "the very first tick already concluded the pet was missing");
        require(PetPresenceProbe.classify(false, true, confirm - 1) == PetPresenceProbe.Presence.PENDING,
                "the confirmation window was not honoured");
        require(PetPresenceProbe.classify(false, true, confirm) == PetPresenceProbe.Presence.MISSING,
                "the pet was not reported missing once the confirmation window elapsed");
        require(PetPresenceProbe.classify(false, true, max) == PetPresenceProbe.Presence.MISSING,
                "a long observation did not stay missing");
        checks += 4;

        // -- 边界：确认窗口之前不允许出现 MISSING --
        for (int tick = 0; tick < confirm; tick++) {
            require(PetPresenceProbe.classify(false, true, tick) != PetPresenceProbe.Presence.MISSING,
                    "MISSING was reported early at tick " + tick);
        }
        checks++;

        System.out.println("PetPresenceProbeSmokeTest: " + checks + "/" + checks + " passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
