# 中文字体设置指南

## 概述
本模组已集成中文字体支持，确保在Minecraft中正确显示中文字符。

## 自动设置
模组启动时会自动：
1. 设置UTF-8编码
2. 初始化字体目录
3. 加载中文字体文件

## 手动设置（可选）

### 1. 字体文件位置
将中文字体文件放置在以下目录：
```
.minecraft/fonts/
```

### 2. 支持的字体格式
- TTF (TrueType Font)
- OTF (OpenType Font)

### 3. 推荐字体
- Noto Sans SC (Google)
- Source Han Sans (Adobe)
- Microsoft YaHei (微软雅黑)

## 配置选项

### 在模组配置中设置字体
```json
{
  "font": {
    "chinese_font": "NotoSansSC-Regular.ttf",
    "font_size": 11.0,
    "enable_antialiasing": true
  }
}
```

## 故障排除

### 问题1：中文显示为方块
**解决方案：**
1. 确保字体文件存在且未损坏
2. 检查字体文件是否支持中文字符
3. 重启Minecraft客户端

### 问题2：字体文件加载失败
**解决方案：**
1. 检查字体文件权限
2. 确保字体目录可写
3. 查看日志文件获取详细错误信息

### 问题3：字体渲染模糊
**解决方案：**
1. 调整字体大小设置
2. 启用抗锯齿
3. 使用高分辨率字体文件

## 开发信息

### 字体工具类
使用 `FontUtil` 类进行字体相关操作：
```java
// 初始化中文字体
FontUtil.initializeChineseFont();

// 检查是否支持中文
boolean supportsChinese = FontUtil.supportsChinese(text);

// 获取最佳字体大小
float fontSize = FontUtil.getOptimalFontSize(text, maxWidth);
```

### 语言文件
中文翻译位于：
```
src/main/resources/assets/truly_best_friends/lang/zh_cn.json
```

## 联系支持
如果遇到字体相关问题，请：
1. 查看日志文件
2. 检查字体文件完整性
3. 联系模组作者

