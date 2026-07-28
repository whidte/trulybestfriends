package com.whidte.trulybestfriends;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.UUID;
import java.util.function.BiPredicate;

/** Mutates fields stored inside one pet UUID node without replacing sibling data. */
public final class PetIndexState {
    private PetIndexState() {}

    public static boolean setRecalled(CompoundTag state, boolean recalled) {
        if (state.contains("Recalled") && state.getBoolean("Recalled") == recalled) return false;
        state.putBoolean("Recalled", recalled);
        return true;
    }

    public static CompoundTag find(CompoundTag index, UUID petUuid) {
        CompoundTag[] found = {null};
        visit(index, (uuid, state) -> {
            if (!petUuid.equals(uuid)) return false;
            found[0] = state;
            return true;
        });
        return found[0];
    }

    public static boolean visit(CompoundTag index, BiPredicate<UUID, CompoundTag> visitor) {
        for (String playerName : index.getAllKeys()) {
            if (!index.contains(playerName, Tag.TAG_COMPOUND)) continue;
            CompoundTag player = index.getCompound(playerName);
            for (String typeKey : player.getAllKeys()) {
                if (!player.contains(typeKey, Tag.TAG_COMPOUND)) continue;
                CompoundTag type = player.getCompound(typeKey);
                for (String uuidText : type.getAllKeys()) {
                    if (!type.contains(uuidText, Tag.TAG_COMPOUND)) continue;
                    try {
                        if (visitor.test(UUID.fromString(uuidText), type.getCompound(uuidText))) return true;
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        }
        return false;
    }
}
