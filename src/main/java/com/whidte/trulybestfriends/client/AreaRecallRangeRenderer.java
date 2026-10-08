package com.whidte.trulybestfriends.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;

/**
 * 按住群体收回键时，用末影人同款紫色粒子在玩家周围勾勒出收回半径。
 *
 * <p>粒子沿圆周采样后投到地表附近，因此既能看到范围大小，
 * 也能看到地形起伏；半径越大采样点越多，视觉密度保持稳定。</p>
 */
public final class AreaRecallRangeRenderer {
    /** 圆周上的基准采样点数（对应当前最小半径）。 */
    private static final int BASE_SAMPLE_COUNT = 32;
    /** 采样点数上限，避免大半径时光是粒子就拖慢帧率。 */
    private static final int MAX_SAMPLE_COUNT = 192;
    /** 每帧投放到世界中的粒子数，避免每帧刷满粒子。 */
    private static final int PARTICLES_PER_FRAME = 10;
    /** 向下寻找地表的最远距离（方块）。 */
    private static final double SURFACE_SEARCH_DEPTH = 6.0D;
    /** 超过该距离的粒子不投放，交给渲染距离自然裁剪。 */
    private static final double MAX_PARTICLE_DISTANCE = 64.0D;

    private static int cursor;

    private AreaRecallRangeRenderer() {}

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        if (!AreaRecallKeyHandler.isHolding()) return;

        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        Player player = minecraft.player;
        if (level == null || player == null) return;

        double range = AreaRecallKeyHandler.currentRange();
        int samples = Math.min(MAX_SAMPLE_COUNT,
                Math.max(BASE_SAMPLE_COUNT, (int) Math.round(range * 12.0D)));
        Vec3 cameraPos = event.getCamera().getPosition();

        // 每帧沿圆周铺开若干颗粒子，转一圈需要若干帧，避免粒子爆炸。
        for (int i = 0; i < PARTICLES_PER_FRAME; i++) {
            double angle = 2.0D * Math.PI * cursor / samples;
            cursor = (cursor + 1) % samples;
            spawnAt(level, player, range, angle, cameraPos);
        }
    }

    /** 把圆周上的采样点投到地面后投放一颗粒子。 */
    private static void spawnAt(ClientLevel level, Player player, double range, double angle,
                                Vec3 cameraPos) {
        double x = player.getX() + Math.cos(angle) * range;
        double z = player.getZ() + Math.sin(angle) * range;
        double y = surfaceY(level, player, x, z);
        // 只投放镜头附近的粒子，远端交给渲染距离自然裁剪。
        double dx = x - cameraPos.x;
        double dy = y - cameraPos.y;
        double dz = z - cameraPos.z;
        if (dx * dx + dy * dy + dz * dz > MAX_PARTICLE_DISTANCE * MAX_PARTICLE_DISTANCE) return;
        // 用短命版本，粒子很快消失，圆周上始终只有最新的那一圈。
        level.addParticle(ModParticleTypes.shortPortal(), x, y + 0.1D, z,
                0.0D, 0.0D, 0.0D);
    }

    /** 从玩家高度向下打一条射线找地面；打空时退回玩家脚下。 */
    private static double surfaceY(ClientLevel level, Player player, double x, double z) {
        double startY = player.getY();
        BlockHitResult hit = level.clip(new ClipContext(
                new Vec3(x, startY, z), new Vec3(x, startY - SURFACE_SEARCH_DEPTH, z),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.MISS ? startY : hit.getLocation().y;
    }
}
