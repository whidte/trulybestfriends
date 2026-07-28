package com.whidte.trulybestfriends;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class OwnerNbtResolver {
    private OwnerNbtResolver() {}

    static boolean isValidPath(String path) {
        if (path == null || path.isEmpty()) return false;
        for (String segment : path.split("\\.", -1)) {
            if (segment.isEmpty()) return false;
        }
        return true;
    }

    static List<String[]> parsePaths(List<? extends String> configuredPaths) {
        List<String[]> parsedPaths = new ArrayList<>(configuredPaths.size());
        for (String path : configuredPaths) {
            if (isValidPath(path)) parsedPaths.add(path.split("\\.", -1));
        }
        return List.copyOf(parsedPaths);
    }

    static UUID resolve(CompoundTag root, List<String[]> paths) {
        for (String[] path : paths) {
            CompoundTag current = root;
            for (int i = 0; i < path.length - 1; i++) {
                if (!current.contains(path[i], Tag.TAG_COMPOUND)) {
                    current = null;
                    break;
                }
                current = current.getCompound(path[i]);
            }
            if (current == null) continue;

            String ownerField = path[path.length - 1];
            if (current.hasUUID(ownerField)) return current.getUUID(ownerField);
            if (current.contains(ownerField, Tag.TAG_STRING)) {
                try {
                    return UUID.fromString(current.getString(ownerField));
                } catch (IllegalArgumentException ignored) {
                    // Try the next configured path.
                }
            }
        }
        return null;
    }
}
