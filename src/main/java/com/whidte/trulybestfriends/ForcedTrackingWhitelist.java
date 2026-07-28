package com.whidte.trulybestfriends;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Pure NBT operations for entities force-tracked by /tbf load master. */
final class ForcedTrackingWhitelist {
    static final String KEY = "TBF_ForcedTrackingWhitelist";

    private ForcedTrackingWhitelist() {}

    static boolean put(CompoundTag indexTag, UUID entityUUID, UUID ownerUUID) {
        CompoundTag whitelist = indexTag.getCompound(KEY);
        String entityKey = entityUUID.toString();
        String ownerValue = ownerUUID.toString();
        if (whitelist.contains(entityKey, Tag.TAG_STRING)
                && ownerValue.equals(whitelist.getString(entityKey))) {
            return false;
        }
        whitelist.putString(entityKey, ownerValue);
        indexTag.put(KEY, whitelist);
        return true;
    }

    static boolean remove(CompoundTag indexTag, UUID entityUUID) {
        CompoundTag whitelist = indexTag.getCompound(KEY);
        String entityKey = entityUUID.toString();
        if (!whitelist.contains(entityKey)) return false;
        whitelist.remove(entityKey);
        if (whitelist.isEmpty()) indexTag.remove(KEY);
        else indexTag.put(KEY, whitelist);
        return true;
    }

    static UUID get(CompoundTag indexTag, UUID entityUUID) {
        CompoundTag whitelist = indexTag.getCompound(KEY);
        String entityKey = entityUUID.toString();
        if (!whitelist.contains(entityKey, Tag.TAG_STRING)) return null;
        try {
            return UUID.fromString(whitelist.getString(entityKey));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    static Map<UUID, UUID> readAll(CompoundTag indexTag) {
        Map<UUID, UUID> entries = new HashMap<>();
        CompoundTag whitelist = indexTag.getCompound(KEY);
        for (String entityKey : whitelist.getAllKeys()) {
            if (!whitelist.contains(entityKey, Tag.TAG_STRING)) continue;
            try {
                entries.put(UUID.fromString(entityKey), UUID.fromString(whitelist.getString(entityKey)));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return entries;
    }
}
