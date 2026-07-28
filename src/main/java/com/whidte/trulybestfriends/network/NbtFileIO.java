package com.whidte.trulybestfriends.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Bridges the pre-1.21 File-based NBT calls to the current Path API. */
public final class NbtFileIO {
    private NbtFileIO() {}

    public static CompoundTag readCompressed(File file) throws IOException {
        return NbtIo.readCompressed(file.toPath(), NbtAccounter.unlimitedHeap());
    }

    public static void writeCompressed(CompoundTag tag, File file) throws IOException {
        Path target = file.toPath().toAbsolutePath();
        Path parent = target.getParent();
        if (parent == null) throw new IOException("NBT target has no parent directory: " + target);
        Files.createDirectories(parent);

        Path temporary = Files.createTempFile(parent, target.getFileName() + ".", ".tmp");
        try {
            NbtIo.writeCompressed(tag, temporary);
            try {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicFailure) {
                // Windows/JDK 21 can report AccessDeniedException rather than
                // AtomicMoveNotSupportedException when atomically replacing an existing file.
                try {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException replacementFailure) {
                    replacementFailure.addSuppressed(atomicFailure);
                    // An open reader may deny the replacement semantics used by both
                    // move variants on Windows. Stream the completed temp file into the
                    // existing target only as a last, non-atomic fallback.
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
