package com.whidte.trulybestfriends.util;

import com.whidte.trulybestfriends.trulybestfriends;

import java.nio.file.Files;
import java.nio.file.Path;

public class FileUtil {
    public static void createDirectoriesSafe(Path path) {
        try {
            Files.createDirectories(path);
        } catch (Exception e) {
            trulybestfriends.LOGGER.error("FAILED:{}", path.toString());
        }
    }
}