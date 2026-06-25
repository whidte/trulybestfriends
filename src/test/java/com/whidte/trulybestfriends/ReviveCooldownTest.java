package com.whidte.trulybestfriends;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 复活冷却时间逻辑测试。
 *
 * 模拟 RevivePetPacket.handle() 中冷却判定的核心逻辑（基于 LastDeathTime），
 * 以及 trulybestfriends.writePetData() 中 LastDeathTime 保留逻辑。
 *
 * 纯 Java，不依赖 Minecraft/Forge 运行时。
 */
public class ReviveCooldownTest {

    public static void main(String[] args) {
        int passed = 0, total = 0;

        // ── 冷却判定逻辑 (基于 LastDeathTime) ─────────────────────────────
        // 宠物死亡时 savePetData 写入 LastDeathTime = System.currentTimeMillis()
        // 冷却从死亡时刻开始计算

        passed += test("首次死亡-无历史记录-应允许复活", () -> {
            MockNbt nbt = deadPetNbt(); // Health=0, 无 LastDeathTime
            return canRevive(nbt, 120, 0) == true;
        });
        total++;

        passed += test("死亡后冷却期内-应拒绝复活", () -> {
            long deathTime = nowMinusSeconds(30); // 30 秒前死亡
            MockNbt nbt = deadPetNbtWithLastDeath(deathTime);
            return canRevive(nbt, 120, 0) == false;
        });
        total++;

        passed += test("死亡后冷却刚到期-应允许复活", () -> {
            long deathTime = nowMinusSeconds(120); // 刚好 120 秒前死亡
            MockNbt nbt = deadPetNbtWithLastDeath(deathTime);
            return canRevive(nbt, 120, 0) == true;
        });
        total++;

        passed += test("死亡后冷却已过-应允许复活", () -> {
            long deathTime = nowMinusSeconds(121); // 121 秒前死亡
            MockNbt nbt = deadPetNbtWithLastDeath(deathTime);
            return canRevive(nbt, 120, 0) == true;
        });
        total++;

        passed += test("冷却配置为0-允许复活", () -> {
            long deathTime = nowMinusSeconds(1); // 1 秒前死亡
            MockNbt nbt = deadPetNbtWithLastDeath(deathTime);
            return canRevive(nbt, 0, 0) == true;
        });
        total++;

        passed += test("冷却配置为0-无死亡记录也允许", () -> {
            MockNbt nbt = deadPetNbt();
            return canRevive(nbt, 0, 0) == true;
        });
        total++;

        // ── NBT 保留逻辑 (writePetData) ─────────────────────────────────
        passed += test("writePetData-保留已有LastDeathTime", () -> {
            long oldDeathTime = System.currentTimeMillis() - 50_000;
            MockNbt oldNbt = new MockNbt();
            oldNbt.putLong("LastDeathTime", oldDeathTime);
            oldNbt.putFloat("Health", 0.0f); // dead

            MockNbt newNbt = writePetDataSimulate(new MockNbt(), oldNbt);
            return newNbt.getLong("LastDeathTime") == oldDeathTime;
        });
        total++;

        passed += test("writePetData-无历史不写入0", () -> {
            MockNbt oldNbt = new MockNbt();
            oldNbt.putFloat("Health", 0.0f);
            MockNbt newNbt = writePetDataSimulate(new MockNbt(), oldNbt);
            return !newNbt.contains("LastDeathTime") || newNbt.getLong("LastDeathTime") == 0L;
        });
        total++;

        passed += test("writePetData-LastDeathTime=0不保留", () -> {
            MockNbt oldNbt = new MockNbt();
            oldNbt.putLong("LastDeathTime", 0L);
            oldNbt.putFloat("Health", 0.0f);
            MockNbt newNbt = writePetDataSimulate(new MockNbt(), oldNbt);
            return !newNbt.contains("LastDeathTime") || newNbt.getLong("LastDeathTime") == 0L;
        });
        total++;

        passed += test("writePetData-新含LastDeathTime时不覆盖为旧值", () -> {
            long newDeathTime = System.currentTimeMillis() - 10_000;  // 10秒前刚死
            long oldDeathTime = System.currentTimeMillis() - 200_000; // 200秒前上次死
            MockNbt newNbt = new MockNbt();
            newNbt.putLong("LastDeathTime", newDeathTime); // savePetData 写入的新权威值
            newNbt.putFloat("Health", 0.0f);
            MockNbt oldNbt = new MockNbt();
            oldNbt.putLong("LastDeathTime", oldDeathTime); // 磁盘上的旧值
            MockNbt result = writePetDataSimulate(newNbt, oldNbt);
            // 必须保留新值(T2)，不能被旧值(T1)覆盖
            return result.getLong("LastDeathTime") == newDeathTime;
        });
        total++;

        // ── 完整生命周期 ──────────────────────────────────────────────
        passed += test("完整流程-死亡→等待冷却→复活→再死亡→新冷却→新冷却阻止→新冷却过→可复活", () -> {
            // 初始: 宠物死亡，无 LastDeathTime
            MockNbt nbt = deadPetNbt();
            if (!canRevive(nbt, 120, 0)) return false; // 应允许首次复活

            // 模拟复活: 清除死亡标记，恢复生命
            nbt.data.remove("LastDeathTime");
            nbt.putFloat("Health", 20.0f);
            // 此时宠物存活，没有 LastDeathTime

            // 模拟再次死亡: savePetData 写入新的 LastDeathTime
            long newDeathTime = System.currentTimeMillis() - 30_000; // 30 秒前死亡
            nbt.putFloat("Health", 0.0f);
            nbt.putLong("LastDeathTime", newDeathTime);

            // 冷却期内尝试复活 → 应拒绝
            if (canRevive(nbt, 120, 0)) return false;

            // 冷却期过后 → 应允许
            return canRevive(nbt, 120, 121_000); // +121s 偏移模拟已过121秒
        });
        total++;

        int failed = total - passed;
        System.out.println();
        System.out.println("=== ReviveCooldownTest: " + passed + "/" + total + " passed"
                + (failed > 0 ? ", " + failed + " FAILED" : "") + " ===");
        if (failed > 0) System.exit(1);
    }

    // ──────────────── 模拟 NBT ────────────────────

    /** 模拟 CompoundTag，只存储我们关心的字段 */
    static class MockNbt {
        final Map<String, Object> data = new HashMap<>();

        void putFloat(String key, float v) { data.put(key, v); }
        float getFloat(String key) { return data.get(key) instanceof Float f ? f : 0f; }

        void putLong(String key, long v) { data.put(key, v); }
        long getLong(String key) { return data.get(key) instanceof Long l ? l : 0L; }

        boolean contains(String key) { return data.containsKey(key); }
    }

    // ──────────────── 冷却判定 (模拟 RevivePetPacket.handle) ──────────

    /**
     * 模拟服务端 RevivePetPacket.handle 中的冷却判定逻辑。
     * 冷却基于 LastDeathTime（宠物死亡时的时间戳）。
     * @param nbt 宠物 NBT
     * @param cooldownSeconds 冷却秒数配置
     * @param timeOffsetMs 时间偏移，模拟从当前时间的偏移
     *                     (now = System.currentTimeMillis() + timeOffsetMs)
     */
    static boolean canRevive(MockNbt nbt, int cooldownSeconds, long timeOffsetMs) {
        long now = System.currentTimeMillis() + timeOffsetMs;

        // Only revive if actually dead
        if (!nbt.contains("Health") || nbt.getFloat("Health") > 0) return false;

        long reviveCooldownMs = cooldownSeconds * 1000L;
        if (reviveCooldownMs > 0 && nbt.contains("LastDeathTime")
                && now - nbt.getLong("LastDeathTime") < reviveCooldownMs) {
            return false; // cooldown active
        }
        return true;
    }

    // ──────────────── 数据保留 (模拟 writePetData) ────────────────────

    /**
     * 模拟 trulybestfriends.writePetData() 中的保留逻辑：
     * 仅当新 NBT 不含 LastDeathTime 时，才从旧 NBT 保留它。
     * savePetData 在实体死亡时写入的 LastDeathTime 是权威时间戳。
     */
    static MockNbt writePetDataSimulate(MockNbt newNbt, MockNbt oldNbt) {
        long existingLastDeathTime = 0L;
        if (oldNbt.contains("LastDeathTime")) {
            existingLastDeathTime = oldNbt.getLong("LastDeathTime");
        }

        // 仅当新 NBT 没有 LastDeathTime 时才保留旧的
        if (!newNbt.contains("LastDeathTime") && existingLastDeathTime > 0L) {
            newNbt.putLong("LastDeathTime", existingLastDeathTime);
        }
        return newNbt;
    }

    // ──────────────── 辅助方法 ────────────────────

    static MockNbt deadPetNbt() {
        MockNbt nbt = new MockNbt();
        nbt.putFloat("Health", 0.0f);
        return nbt;
    }

    static MockNbt deadPetNbtWithLastDeath(long lastDeathTime) {
        MockNbt nbt = new MockNbt();
        nbt.putFloat("Health", 0.0f);
        nbt.putLong("LastDeathTime", lastDeathTime);
        return nbt;
    }

    static long nowMinusSeconds(int seconds) {
        return System.currentTimeMillis() - seconds * 1000L;
    }

    static int test(String desc, java.util.function.BooleanSupplier fn) {
        boolean ok;
        try {
            ok = fn.getAsBoolean();
        } catch (Exception e) {
            System.out.println("[FAIL] " + desc + " → 异常: " + e.getMessage());
            return 0;
        }
        System.out.println((ok ? "[PASS]" : "[FAIL]") + " " + desc);
        return ok ? 1 : 0;
    }
}
