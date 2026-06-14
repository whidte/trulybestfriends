package com.whidte.trulybestfriends.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.FontManager;
import net.minecraft.resources.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 中文字体工具类
 * 提供字体加载、渲染等实用方法
 */
public class FontUtil {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final String FONT_DIR = "fonts";
    private static final String DEFAULT_CHINESE_FONT = "NotoSansSC-Regular.ttf";
    
    /**
     * 初始化中文字体
     */
    public static void initializeChineseFont() {
        try {
            // 确保字体目录存在
            createFontDirectory();
            
            // 复制默认中文字体文件
            copyDefaultFont();
            
            LOGGER.info("中文字体初始化完成");
        } catch (Exception e) {
            LOGGER.error("中文字体初始化失败: {}", e.getMessage());
        }
    }
    
    /**
     * 创建字体目录
     */
    private static void createFontDirectory() throws IOException {
        Path fontDir = getFontDirectory();
        if (!Files.exists(fontDir)) {
            Files.createDirectories(fontDir);
            LOGGER.info("创建字体目录: {}", fontDir);
        }
    }
    
    /**
     * 复制默认字体文件
     */
    private static void copyDefaultFont() throws IOException {
        Path fontFile = getFontDirectory().resolve(DEFAULT_CHINESE_FONT);
        
        if (!Files.exists(fontFile)) {
            // 从模组资源中复制字体文件
            ResourceLocation fontResource = ResourceLocation.fromNamespaceAndPath("truly_best_friends", "font/" + DEFAULT_CHINESE_FONT);
            
            try (InputStream inputStream = Minecraft.getInstance().getResourceManager()
                    .getResource(fontResource).orElseThrow().open()) {
                Files.copy(inputStream, fontFile, StandardCopyOption.REPLACE_EXISTING);
                LOGGER.info("复制字体文件: {}", fontFile);
            } catch (Exception e) {
                LOGGER.warn("无法从资源加载字体文件，将使用系统默认字体");
            }
        }
    }
    
    /**
     * 获取字体目录路径
     */
    public static Path getFontDirectory() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve(FONT_DIR);
    }
    
    /**
     * 检查是否支持中文字符
     */
    public static boolean supportsChinese(String text) {
        return text.matches(".*[\\u4E00-\\u9FFF]+.*");
    }
    
    /**
     * 获取适合的字体大小
     */
    public static float getOptimalFontSize(String text, float maxWidth) {
        Font font = Minecraft.getInstance().font;
        float fontSize = 1.0f;
        
        while (font.width(text) * fontSize < maxWidth && fontSize < 2.0f) {
            fontSize += 0.1f;
        }
        
        return Math.min(fontSize, 2.0f);
    }
}

