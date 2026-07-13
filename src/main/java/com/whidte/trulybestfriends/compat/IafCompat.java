package com.whidte.trulybestfriends.compat;

import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.fml.ModList;

import java.lang.reflect.Method;

/**
 * Ice and Fire 联动层。
 *
 * <p>IaF 是 {@code runtimeOnly} 依赖（见 build.gradle），编译期不可直接引用其类。
 * 本类通过反射 + {@link ModList#isLoaded(String)} 守卫访问 IaF 的尸体机制，
 * 模式与 {@code tab.L2TabsIntegration} 一致：IaF 未安装时所有方法静默返回安全默认值，
 * 不影响模组其余功能。</p>
 *
 * <h3>解决的冲突</h3>
 * <p>IaF 的龙死亡后不会消失，而是变成同 UUID 的"尸体实体"长期驻留世界
 * （{@code EntityDragonBase#tickDeath} 每 tick 把 {@code deathTime} 归零阻止原版移除，
 * 并 {@code setModelDead(true)} 切换为尸体模型）。TBF 原本的复活逻辑用
 * {@code addFreshEntity} 新建同 UUID 实体，与"尸体还在"冲突 → 复活失败或 UUID 重复。
 * 本类提供 {@link #clearCorpseState} 在复活时原地清除尸体状态，避免新建实体。</p>
 *
 * <h3>覆盖范围</h3>
 * <p>通过 {@code IDeadMob} 接口判定，自动覆盖所有 IaF 尸体类生物
 * （火龙 / 冰龙 / 雷龙 / 独眼巨人等），而非硬编码实体 ID。</p>
 */
public final class IafCompat {

    private IafCompat() {}

    /** IaF 的 mod id。 */
    private static final String MOD_ID = "iceandfire";

    /** IaF 尸体标记接口的全限定名。 */
    private static final String DEAD_MOB_CLASS = "com.github.alexthe666.iceandfire.entity.util.IDeadMob";

    /** 单次初始化结果（volatile 保证多线程可见，init 只做一次重活）。 */
    private static volatile boolean initialized;
    private static boolean loaded;
    private static Class<?> deadMobClass;
    private static Method isMobDeadMethod;

    private static void init() {
        if (initialized) return;
        synchronized (IafCompat.class) {
            if (initialized) return;
            initialized = true;
            if (!ModList.get().isLoaded(MOD_ID)) {
                loaded = false;
                return;
            }
            try {
                deadMobClass = Class.forName(DEAD_MOB_CLASS);
                isMobDeadMethod = deadMobClass.getMethod("isMobDead");
                loaded = true;
                trulybestfriends.LOGGER.info("IafCompat: Ice and Fire detected, corpse compatibility enabled.");
            } catch (Exception e) {
                loaded = false;
                trulybestfriends.LOGGER.warn("IafCompat: Ice and Fire present but IDeadMob unavailable, compatibility disabled: {}", e.toString());
            }
        }
    }

    /** IaF 是否已安装且接口可用。 */
    public static boolean isLoaded() {
        init();
        return loaded;
    }

    /**
     * 实体是否为 IaF 尸体类生物（龙、独眼巨人等实现 {@code IDeadMob} 的实体）。
     * IaF 未加载时返回 false。
     */
    public static boolean isDeadMob(Entity entity) {
        if (!isLoaded() || entity == null) return false;
        return deadMobClass.isInstance(entity);
    }

    /**
     * 实体是否为 IaF 尸体且当前处于死亡（尸体）状态。
     * IaF 未加载或实体非 IDeadMob 时返回 false。
     */
    public static boolean isMobDead(Entity entity) {
        if (!isDeadMob(entity)) return false;
        try {
            Object result = isMobDeadMethod.invoke(entity);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            trulybestfriends.LOGGER.debug("IafCompat.isMobDark invoke failed on {}: {}", entity.getClass().getName(), e.toString());
            return false;
        }
    }

    /**
     * 在原地清除 IaF 尸体状态，让尸体实体"复活"为正常活体。
     *
     * <p>调用者（{@code RevivePetPacket}）负责后续的通用复活步骤：
     * {@code setHealth}、清死亡标记、施加图腾效果、传送到玩家旁、写回磁盘 NBT。
     * 本方法只做 IaF 专属的两件事：{@code setModelDead(false)} + {@code setDeathStage(0)}。</p>
     *
     * @param entity 必须是 {@link #isMobDead} 返回 true 的实体
     * @return 成功清除尸体状态返回 true；实体非尸体或反射失败返回 false
     */
    public static boolean clearCorpseState(LivingEntity entity) {
        if (!isMobDead(entity)) return false;
        try {
            Method setModelDead = findMethod(entity.getClass(), "setModelDead", boolean.class);
            Method setDeathStage = findMethod(entity.getClass(), "setDeathStage", int.class);
            if (setModelDead == null || setDeathStage == null) {
                trulybestfriends.LOGGER.warn("IafCompat.clearCorpseState: setModelDead/setDeathStage not found on {}", entity.getClass().getName());
                return false;
            }
            setModelDead.invoke(entity, false);
            setDeathStage.invoke(entity, 0);
            return true;
        } catch (Exception e) {
            trulybestfriends.LOGGER.warn("IafCompat.clearCorpseState failed on {}: {}", entity.getClass().getName(), e.toString());
            return false;
        }
    }

    /** 沿类层次向上查找方法（IaF 的 setter 在 {@code EntityDragonBase}，子类 {@code EntityFireDragon} 等不重写）。 */
    private static Method findMethod(Class<?> clazz, String name, Class<?>... params) {
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            try {
                return c.getMethod(name, params);
            } catch (NoSuchMethodException e) {
                c = c.getSuperclass();
            }
        }
        return null;
    }
}
