package com.whidte.trulybestfriends;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class OwnerNbtResolver {
    private OwnerNbtResolver() {}

    static boolean isValidPath(String path) {
        return splitPath(path) != null;
    }

    static List<String[]> parsePaths(List<? extends String> configuredPaths) {
        List<String[]> parsedPaths = new ArrayList<>(configuredPaths.size());
        for (String path : configuredPaths) {
            String[] segments = splitPath(path);
            if (segments != null) parsedPaths.add(segments);
        }
        return List.copyOf(parsedPaths);
    }

    /** 分割并校验路径，避免配置解析时对同一字符串重复分割。 */
    private static String[] splitPath(String path) {
        if (path == null || path.isEmpty()) return null;
        String[] segments = path.split("\\.", -1);
        for (String segment : segments) {
            if (segment.isEmpty()) return null;
        }
        return segments;
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
                    // 尝试下一个配置的路径。
                }
            }
        }
        return null;
    }
}
