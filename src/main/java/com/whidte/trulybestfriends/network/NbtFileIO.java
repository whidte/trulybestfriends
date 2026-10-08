package com.whidte.trulybestfriends.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** 为 Forge 1.20.1 的 File API 提供原子的压缩 NBT 写入。 */
public final class NbtFileIO {
    private NbtFileIO() {}

    public static CompoundTag readCompressed(File file) throws IOException {
        return NbtIo.readCompressed(file);
    }

    public static void writeCompressed(CompoundTag tag, File file) throws IOException {
        Path target = file.toPath().toAbsolutePath();
        Path parent = target.getParent();
        if (parent == null) throw new IOException("NBT target has no parent directory: " + target);
        Files.createDirectories(parent);

        Path temporary = Files.createTempFile(parent, target.getFileName() + ".", ".tmp");
        try {
            NbtIo.writeCompressed(tag, temporary.toFile());
            try {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicFailure) {
                // 在原子替换已存在的文件时，Windows/JDK 21 可能报告
                // AccessDeniedException 而不是 AtomicMoveNotSupportedException。
                try {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException replacementFailure) {
                    replacementFailure.addSuppressed(atomicFailure);
                    // 在 Windows 上，处于打开状态的读取方可能拒绝两种 move 变体
                    // 所用的替换语义。仅在最后作为非原子的兜底手段时，
                    // 才把已完成的临时文件流式写入现有目标。
                    try {
                        try (var output = Files.newOutputStream(target,
                                StandardOpenOption.WRITE,
                                StandardOpenOption.TRUNCATE_EXISTING)) {
                            Files.copy(temporary, output);
                        }
                    } catch (IOException copyFailure) {
                        copyFailure.addSuppressed(replacementFailure);
                        throw copyFailure;
                    }
                }
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
