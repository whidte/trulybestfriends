package com.whidte.trulybestfriends;

import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/** 验证 TBF 自有的主人字段：双重读取、旧字符串修复，以及不覆盖上游字段。 */
public final class TbfOwnerTagTest {
    private TbfOwnerTagTest() {}

    public static void main(String[] args) {
        int checks = 0;

        // -- 经由 TBF 自有键的往返 --
        CompoundTag tag = new CompoundTag();
        UUID owner = UUID.randomUUID();
        TbfOwnerTag.write(tag, owner);
        require(owner.equals(TbfOwnerTag.read(tag)), "own key did not round-trip as a UUID");
        require(owner.toString().equals(TbfOwnerTag.readString(tag)),
                "own key did not round-trip as a string");
        require(!TbfOwnerTag.hasLegacyString(tag), "own key was mistaken for a legacy string");
        checks += 3;

        // -- 写入不得触碰任何类型的上游键 --
        CompoundTag upstream = new CompoundTag();
        UUID upstreamOwner = UUID.randomUUID();
        upstream.putUUID("OwnerUUID", upstreamOwner);
        TbfOwnerTag.write(upstream, owner);
        require(upstream.hasUUID("OwnerUUID") && upstreamOwner.equals(upstream.getUUID("OwnerUUID")),
                "writing TBF's own key replaced the entity's UUID-tagged owner");
        require(owner.equals(TbfOwnerTag.read(upstream)), "TBF's own key was not preferred");
        checks += 2;

        // -- 上游 UUID 标签绝不能被读取为 TBF 的主人 --
        CompoundTag upstreamOnly = new CompoundTag();
        UUID entityOwner = UUID.randomUUID();
        upstreamOnly.putUUID("OwnerUUID", entityOwner);
        require(TbfOwnerTag.readString(upstreamOnly).isEmpty(),
                "an upstream UUID tag was read as TBF's string owner");
        require(TbfOwnerTag.read(upstreamOnly) == null,
                "an upstream UUID tag was parsed as TBF's owner");
        require(!TbfOwnerTag.hasLegacyString(upstreamOnly),
                "an upstream UUID tag was flagged as a legacy string");
        checks += 3;

        // -- 旧快照：改名之前位于上游键下的 TBF 字符串 --
        CompoundTag legacy = new CompoundTag();
        UUID legacyOwner = UUID.randomUUID();
        legacy.putString("OwnerUUID", legacyOwner.toString());
        require(TbfOwnerTag.hasLegacyString(legacy), "legacy string was not detected");
        require(legacyOwner.equals(TbfOwnerTag.read(legacy)), "legacy string was not read back");
        checks += 2;

        CompoundTag repaired = TbfOwnerTag.repairedLegacyOwner(legacy, legacyOwner);
        require(repaired != null, "legacy string was not repaired");
        require(repaired.hasUUID("OwnerUUID"), "repair did not produce a UUID tag");
        require(legacyOwner.equals(repaired.getUUID("OwnerUUID")), "repair changed the owner UUID");
        require(!repaired.contains(TbfOwnerTag.KEY), "repair added TBF's own key");
        checks += 4;

        // 源标签必须保持不变，这样即使修复失败也不会破坏数据。
        require(legacy.contains("OwnerUUID", 8) && !legacy.hasUUID("OwnerUUID"),
                "repair modified the source snapshot");
        checks++;

        // -- 修复仅适用于旧字符串形式 --
        require(TbfOwnerTag.repairedLegacyOwner(upstreamOnly, entityOwner) == null,
                "a UUID-tagged owner was needlessly rewritten");
        require(TbfOwnerTag.repairedLegacyOwner(legacy, null) == null,
                "a repair without an owner UUID was produced");
        require(TbfOwnerTag.repairedLegacyOwner(new CompoundTag(), legacyOwner) == null,
                "a repair without a legacy key was produced");
        checks += 3;

        // -- 当快照同时带有两种形式时，新键优先 --
        CompoundTag mixed = new CompoundTag();
        UUID recorded = UUID.randomUUID();
        mixed.putString("OwnerUUID", legacyOwner.toString());
        TbfOwnerTag.write(mixed, recorded);
        require(recorded.equals(TbfOwnerTag.read(mixed)), "legacy string shadowed TBF's own key");
        require(TbfOwnerTag.repairedLegacyOwner(mixed, recorded).getUUID("OwnerUUID").equals(recorded),
                "repair did not use TBF's own recorded owner");
        checks += 2;

        // -- 畸形输入保持无害 --
        CompoundTag malformed = new CompoundTag();
        malformed.putString("OwnerUUID", "not-a-uuid");
        require(TbfOwnerTag.read(malformed) == null, "malformed UUID string was parsed");
        require("not-a-uuid".equals(TbfOwnerTag.readString(malformed)),
                "malformed string was not returned verbatim");
        require(TbfOwnerTag.parse("") == null && TbfOwnerTag.parse(null) == null,
                "empty input was parsed into a UUID");
        require(TbfOwnerTag.readString(null).isEmpty() && TbfOwnerTag.read(null) == null,
                "null tag was not handled");
        require(!TbfOwnerTag.hasLegacyString(null), "null tag was reported as a legacy string");
        checks += 6;

        System.out.println("TbfOwnerTagTest: " + checks + "/" + checks + " passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
