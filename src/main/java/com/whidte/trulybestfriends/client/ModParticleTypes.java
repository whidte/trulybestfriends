package com.whidte.trulybestfriends.client;

import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 群体收回范围所用的短命末影人粒子类型（只在客户端本地生成）。
 *
 * <p>注意：**不要**把挂载总线的方法写成 {@code register(IEventBus)} 再用
 * {@code modEventBus.addListener(ModParticleTypes::register)} 调用。
 * NeoForge 的事件总线靠方法**形参类型**判断监听的事件种类，形参是 {@code IEventBus}
 * 会被解析成 {@code Object}，在 mod 构造阶段直接抛
 * 「java.lang.Object is not a subclass of IModBusEvent」。
 * 也**不要**在静态块里调 {@code FMLJavaModLoadingContext}——该类在 NeoForge 21.1
 * 已被移除（只剩 {@code ModLoadingContext}）。这里由主类显式传入总线。</p>
 */
public final class ModParticleTypes {
    private static final DeferredRegister<ParticleType<?>> PARTICLE_TYPES =
            DeferredRegister.create(Registries.PARTICLE_TYPE, trulybestfriends.MODID);

    /** 短命版末影人传送粒子。 */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> SHORT_PORTAL =
            PARTICLE_TYPES.register("short_portal", () -> new SimpleParticleType(false));

    private ModParticleTypes() {}

    /** 由主类在构造时调用，传入 mod 事件总线。 */
    public static void attach(IEventBus modEventBus) {
        PARTICLE_TYPES.register(modEventBus);
    }

    /** 取出已注册的粒子类型实例，供提供者注册时使用。 */
    public static SimpleParticleType shortPortal() {
        return SHORT_PORTAL.get();
    }
}
