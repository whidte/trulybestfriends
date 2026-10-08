package com.whidte.trulybestfriends.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.SimpleParticleType;

/**
 * 末影人传送粒子的短命版本：外观、颜色与漂移与 {@code PortalParticle} 一致，
 * 但寿命被压到 {@link #LIFETIME_TICKS}，这样按住群体收回键时
 * 每一颗粒子都会很快消失，圆周上始终只有最新的粒子，范围轮廓因此始终清晰。
 *
 * <p>原版 {@code PortalParticle} 的寿命是写死的 40~50 tick（约 2~2.5 秒），
 * 且带向下的漂移，粒子会堆叠成一片糊状，看不清边界。</p>
 */
public class ShortPortalParticle extends TextureSheetParticle {
    /** 粒子存活 tick 数。 */
    public static final int LIFETIME_TICKS = 8;

    private final double startX;
    private final double startY;
    private final double startZ;

    protected ShortPortalParticle(ClientLevel level, double x, double y, double z) {
        super(level, x, y, z);
        this.xo = x;
        this.yo = y;
        this.zo = z;
        this.startX = x;
        this.startY = y;
        this.startZ = z;
        // 与 PortalParticle 相同的尺寸与紫红色调。
        this.quadSize = 0.1F * (this.random.nextFloat() * 0.2F + 0.5F);
        float shade = this.random.nextFloat() * 0.6F + 0.4F;
        this.rCol = shade * 0.9F;
        this.gCol = shade * 0.3F;
        this.bCol = shade;
        this.lifetime = LIFETIME_TICKS;
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_OPAQUE;
    }

    /** 用与 PortalParticle 相同的缓动让粒子向内收敛，但漂移幅度由 DRIFT_SPEED 控制。 */
    @Override
    public void move(double dx, double dy, double dz) {
        this.setBoundingBox(this.getBoundingBox().move(dx, dy, dz));
        this.setLocationFromBoundingbox();
    }

    @Override
    public float getQuadSize(float partialTick) {
        float progress = ((float) this.age + partialTick) / (float) this.lifetime;
        progress = 1.0F - progress;
        progress *= progress;
        progress = 1.0F - progress;
        return this.quadSize * progress;
    }

    @Override
    public int getLightColor(float partialTick) {
        int packed = super.getLightColor(partialTick);
        float progress = (float) this.age / (float) this.lifetime;
        progress *= progress;
        progress *= progress;
        int blue = packed & 255;
        int red = packed >> 16 & 255;
        red += (int) (progress * 15.0F * 16.0F);
        if (red > 240) {
            red = 240;
        }
        return blue | red << 16;
    }

    @Override
    public void tick() {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;
        if (this.age++ >= this.lifetime) {
            this.remove();
            return;
        }
        // 原地不动，只做极轻微的上升：短命期内的位移肉眼几乎不可见，
        // 因此粒子始终钉在圆周上，范围轮廓不会随粒子漂移而模糊。
        float progress = (float) this.age / (float) this.lifetime;
        this.x = this.startX;
        this.y = this.startY + 0.12D * (double) progress;
        this.z = this.startZ;
    }

    /** 由 {@code RegisterParticleProvidersEvent} 注册的提供者。 */
    public static class Provider implements ParticleProvider<SimpleParticleType> {
        @SuppressWarnings("unused")
        private final SpriteSet sprites;

        public Provider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level,
                                       double x, double y, double z,
                                       double dx, double dy, double dz) {
            ShortPortalParticle particle = new ShortPortalParticle(level, x, y, z);
            particle.pickSprite(this.sprites);
            return particle;
        }
    }
}
