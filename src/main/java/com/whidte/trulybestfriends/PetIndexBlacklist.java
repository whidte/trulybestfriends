package com.whidte.trulybestfriends;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.UUID;

/** Pure NBT operations for the persistent pet-index blacklist. */
final class PetIndexBlacklist {
    static final String KEY = "TBF_BlacklistedUUIDs";

    private PetIndexBlacklist() {}

    static boolean add(CompoundTag indexTag, UUID petUUID) {
        ListTag blacklist = indexTag.getList(KEY, Tag.TAG_STRING);
        String uuid = petUUID.toString();
        for (int i = 0; i < blacklist.size(); i++) {
            if (uuid.equals(blacklist.getString(i))) return false;
        }
        blacklist.add(StringTag.valueOf(uuid));
        indexTag.put(KEY, blacklist);
        return true;
    }

    static boolean contains(CompoundTag indexTag, UUID petUUID) {
        String uuid = petUUID.toString();
        ListTag blacklist = indexTag.getList(KEY, Tag.TAG_STRING);
        for (int i = 0; i < blacklist.size(); i++) {
            if (uuid.equals(blacklist.getString(i))) return true;
        }
        return false;
    }
}
