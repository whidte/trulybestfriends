package com.whidte.trulybestfriends;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.UUID;

/**
 * TBF 在存储的实体快照中自己使用的主人字段。
 *
 * <p>旧版本把主人 UUID 以 <em>字符串</em> 形式写入无后缀的键
 * {@code OwnerUUID}。该键属于实体本身：许多模组会在那里存一个真正的
 * UUID 标签并用 {@link CompoundTag#hasUUID} 读回，而它只接受一个由
 * 四个 int 组成的数组。因此 TBF 写入的字符串会悄悄抹掉它们的所有权数据 —— Saint's Dragons 的
 * Ivy Oleander 就会在一次收回后这样丢失她的招募者。</p>
 *
 * <p>快照现在使用 {@link #KEY}，绝不触碰 {@link #LEGACY_KEY}；旧键仍会被
 * <em>读取</em>，以便现有存档继续可用，而 {@link #repairedLegacyOwner} 可以把旧的
 * 被污染形式还原成所属模组能够理解的东西。</p>
 */
public final class TbfOwnerTag {
    /** 从现在起 TBF 写入的键。带命名空间，因此不会与其他模组冲突。 */
    public static final String KEY = "TBF_OwnerUUID";

    /** 旧版 TBF 键。其他模组也原生使用它，因此只读取，绝不写入。 */
    public static final String LEGACY_KEY = "OwnerUUID";

    private TbfOwnerTag() {}

    /** 在 {@link #KEY} 下记录 {@code ownerUUID}，始终采用 TBF 自己的字符串形式。 */
    public static void write(CompoundTag tag, UUID ownerUUID) {
        tag.putString(KEY, ownerUUID.toString());
    }

    /**
     * 记录的主人原始值：优先 {@link #KEY}，然后是 {@link #LEGACY_KEY}，但仅当旧键
     * 存放的是字符串时 —— 那里的 UUID 标签属于实体本身，而不属于 TBF。当两个键都不适用时，
     * 返回空字符串。
     */
    public static String readString(CompoundTag tag) {
        if (tag == null) return "";
        if (tag.contains(KEY, Tag.TAG_STRING)) return tag.getString(KEY);
        if (tag.contains(LEGACY_KEY, Tag.TAG_STRING)) return tag.getString(LEGACY_KEY);
        return "";
    }

    /** 解析后的 {@link #readString}，当缺失或格式错误时返回 {@code null}。 */
    public static UUID read(CompoundTag tag) {
        return parse(readString(tag));
    }

    /** 当旧键仍存放 TBF 的旧字符串形式而非真正的 UUID 标签时返回 true。 */
    public static boolean hasLegacyString(CompoundTag tag) {
        return tag != null && tag.contains(LEGACY_KEY, Tag.TAG_STRING);
    }

    /**
     * {@code tag} 的副本，其中旧键被改写为正规的 UUID 标签，从而所属模组
     * 可以用 {@code hasUUID} 再次读取它。原始标签绝不会被修改。
     *
     * @return 当没有需要修复的内容或 {@code ownerUUID} 未知时返回 {@code null}。
     */
    public static CompoundTag repairedLegacyOwner(CompoundTag tag, UUID ownerUUID) {
        if (ownerUUID == null || !hasLegacyString(tag)) return null;
        CompoundTag repaired = tag.copy();
        repaired.putUUID(LEGACY_KEY, ownerUUID);
        return repaired;
    }

    /** 解析规范的 UUID 字符串，返回 {@code null} 而不是抛出异常。 */
    public static UUID parse(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
