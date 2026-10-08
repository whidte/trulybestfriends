package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.TbfOwnerTag;
import com.whidte.trulybestfriends.compat.SableCompat;
import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.UUID;

/** 捕获并还原完整的实体树，同时保留旧版 TBF 元数据。 */
public final class PetEntitySnapshot {
    /**
     * TBF 唯一曾破坏过的上游键，因此也是它唯一修复或写回的键。
     * 以真正的 UUID 标签写入，因为这才是模组用
     * {@code CompoundTag#hasUUID} 读取的形式。其他主人键（原版的 {@code Owner}、能力路径）
     * 从未被 TBF 触碰，因此刻意不做改动。
     */
    private static final String UPSTREAM_OWNER_KEY = TbfOwnerTag.LEGACY_KEY;

    private PetEntitySnapshot() {}

    public static CompoundTag capture(Entity entity, UUID ownerUUID, ServerLevel level) {
        CompoundTag nbt = new CompoundTag();
        if (!entity.saveAsPassenger(nbt)) {
            entity.saveWithoutId(nbt);
            nbt.putString("id", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
        }

        if (entity instanceof LivingEntity living) {
            nbt.putFloat("MaxHealth", (float) living.getAttributeValue(Attributes.MAX_HEALTH));
        }
        TeleportPetToPlayerPacket.backupChestInventory(entity, nbt);
        // 仅使用 TBF 自己的字段。此处绝不复用上游键：实体自身的 OwnerUUID /
        // Owner 标签完全保留其实体序列化时的原样，因此持久化归属的模组
        // 能在收回后存活，而不会被用另一种 NBT 类型覆写其值。
        TbfOwnerTag.write(nbt, ownerUUID);
        nbt.putString("EntityType", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
        nbt.putString("Dimension", level.dimension().location().toString());
        SableCompat.captureSubLevelInfo(nbt, level, entity.position());
        return copyRootEntityOnly(nbt);
    }

    public static Entity restore(CompoundTag snapshot, UUID expectedUuid, ServerLevel level) {
        return restore(snapshot, expectedUuid, level, null);
    }

    /**
     * 重建已存储的宠物。
     *
     * @param ownerHint TBF 在此快照之外为该宠物记录的主人——通常是加载该 {@code .nbt} 时
     *                  所在目录的名称。仅当快照和实体本身都无法得出主人时才会参考它。
     */
    public static Entity restore(CompoundTag snapshot, UUID expectedUuid, ServerLevel level, UUID ownerHint) {
        CompoundTag entityNbt = copyRootEntityOnly(snapshot);
        if (!entityNbt.contains("id", 8)) {
            String legacyType = entityNbt.getString("EntityType");
            if (legacyType.isEmpty()) return null;
            entityNbt.putString("id", legacyType);
        }

        UUID expectedOwner = TbfOwnerTag.read(entityNbt);
        if (expectedOwner == null) expectedOwner = ownerHint;
        // 完全没有预期值：无法进行任何校验，因此保持普通加载（旧行为）。
        if (expectedOwner == null) {
            return instantiate(entityNbt, expectedUuid, level);
        }

        // 先尝试未修改的快照。新快照需要的就是这种，而且它也能让原生以字符串
        // 存储主人的模组继续工作——重写该标签会破坏这些模组。
        Entity plain = instantiate(entityNbt, expectedUuid, level);
        if (plain == null || ownerMatches(plain, expectedOwner)) return plain;

        // 旧版 TBF 写入的快照把实体的 OwnerUUID 标签替换成了
        // 字符串。把它以真正的 UUID 标签交还，以便拥有它的模组能再次读取。
        CompoundTag repaired = TbfOwnerTag.repairedLegacyOwner(entityNbt, expectedOwner);
        if (repaired != null) {
            Entity candidate = instantiate(repaired, expectedUuid, level);
            if (ownerMatches(candidate, expectedOwner)) return candidate;
        }

        // 该实体不再携带任何可达的主人：把记录的主人写入
        // TBF 曾覆写的那个键，使宠物继续归属其主人，而不是悄然
        // 变成无主。
        if (!entityNbt.contains(UPSTREAM_OWNER_KEY)) {
            CompoundTag adopted = entityNbt.copy();
            adopted.putUUID(UPSTREAM_OWNER_KEY, expectedOwner);
            Entity candidate = instantiate(adopted, expectedUuid, level);
            if (ownerMatches(candidate, expectedOwner)) return candidate;
        }

        return plain;
    }

    private static Entity instantiate(CompoundTag entityNbt, UUID expectedUuid, ServerLevel level) {
        Entity entity = EntityType.loadEntityRecursive(entityNbt, level, loaded -> loaded);
        if (entity != null) entity.setUUID(expectedUuid);
        return entity;
    }

    /**
     * 当实体本身报告 {@code expectedOwner} 为其主人时为 true。校验失败
     * 意味着加载出的实体无法携带 TBF 的归属记录，而这正是
     * 修复与收养流程存在的原因。
     */
    private static boolean ownerMatches(Entity entity, UUID expectedOwner) {
        return entity != null && expectedOwner.equals(trulybestfriends.getEntityOwnerUUID(entity));
    }

    static CompoundTag copyRootEntityOnly(CompoundTag snapshot) {
        CompoundTag root = snapshot.copy();
        root.remove("Passengers");
        return root;
    }
}
