package com.whidte.trulybestfriends.client;

import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.Registries;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/** 群体收回范围所用的短命末影人粒子类型（只在客户端本地生成）。 */
public final class ModParticleTypes {
    private static final DeferredRegister<ParticleType<?>> PARTICLE_TYPES =
            DeferredRegister.create(Registries.PARTICLE_TYPE, trulybestfriends.MODID);

    /** 短命版末影人传送粒子。 */
    public static final RegistryObject<SimpleParticleType> SHORT_PORTAL =
            PARTICLE_TYPES.register("short_portal", () -> new SimpleParticleType(false));

    private ModParticleTypes() {}

    /**
     * 由主类在构造时调用，传入 mod 事件总线。
     *
     * <p>刻意不叫 {@code register}：若日后有人写成
     * {@code modEventBus.addListener(ModParticleTypes::register)}，
     * 事件总线会按形参类型把它解析成 {@code Object} 并抛
     * 「java.lang.Object is not a subclass of IModBusEvent」。</p>
     */
    public static void attach(IEventBus modEventBus) {
        PARTICLE_TYPES.register(modEventBus);
    }

    /** 取出已注册的粒子类型实例，供提供者注册时使用。 */
    public static SimpleParticleType shortPortal() {
        return SHORT_PORTAL.get();
    }
}
