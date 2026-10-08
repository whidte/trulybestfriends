package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.TbfOwnerTag;
import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 群体收回的纯逻辑冒烟测试。
 *
 * <p>覆盖三部分，都不依赖 Minecraft 注册表（裸 JVM 可跑）：</p>
 * <ol>
 *   <li>「&lt;名&gt;、&lt;名&gt;… 已被收回」的合并格式。</li>
 *   <li>每个宠物名都挂上了「点击打开标签页并选中它」的点击/悬浮事件。</li>
 *   <li>「只收回自己的宠物」所用的归属解析：{@code getSnapshotOwnerUUID}
 *       优先读 {@code TBF_OwnerUUID}。只测该命中分支——回退到
 *       {@code Config.ownerNbtPaths} 的分支会加载 {@code Config}（内含
 *       {@code ModConfigSpec}），裸 JVM 下不安全。</li>
 * </ol>
 */
public final class AreaRecallMessageSmokeTest {
    private AreaRecallMessageSmokeTest() {}

    private static final UUID FIRST = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID SECOND = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");

    public static void main(String[] args) {
        testSingleName();
        testMultipleNames();
        testNoTrailingSeparator();
        testNamesAreClickable();
        testSnapshotOwnerFromTbfTag();
        System.out.println("AreaRecallMessageSmokeTest: passed");
    }

    private static void testSingleName() {
        Component message = AreaRecallPacket.recallMessage(List.of(pet(FIRST, "小马")));
        require("小马 已被收回".equals(message.getString()),
                "single-name message mismatch: " + message.getString());
    }

    private static void testMultipleNames() {
        Component message = AreaRecallPacket.recallMessage(List.of(
                pet(FIRST, "小马"), pet(SECOND, "小白"), pet(FIRST, "小黑")));
        require("小马、小白、小黑 已被收回".equals(message.getString()),
                "multi-name message mismatch: " + message.getString());
    }

    /** 名字之间用「、」连接，末尾不能多出一个分隔符。 */
    private static void testNoTrailingSeparator() {
        Component message = AreaRecallPacket.recallMessage(List.of(
                pet(FIRST, "A"), pet(SECOND, "B")));
        require("A、B 已被收回".equals(message.getString()),
                "separator placement mismatch: " + message.getString());
        require(message.getString().indexOf('、') == message.getString().lastIndexOf('、'),
                "more than one separator between two names: " + message.getString());
    }

    /**
     * 每个名字都必须带 RUN_COMMAND 点击事件（命令里含该宠物 UUID），
     * 否则「点击名字打开标签页」这个功能在服务端悄悄失效、且没有任何报错。
     */
    private static void testNamesAreClickable() {
        Component message = AreaRecallPacket.recallMessage(List.of(
                pet(FIRST, "小马"), pet(SECOND, "小白")));

        List<Component> clickable = new ArrayList<>();
        for (Component sibling : message.getSiblings()) {
            if (sibling.getStyle().getClickEvent() != null) clickable.add(sibling);
        }
        require(clickable.size() == 2,
                "expected two clickable names, got " + clickable.size());

        requireClickCommand(clickable.get(0), "小马", FIRST);
        requireClickCommand(clickable.get(1), "小白", SECOND);
    }

    private static void requireClickCommand(Component name, String expectedText, UUID uuid) {
        require(expectedText.equals(name.getString()),
                "clickable name text mismatch: " + name.getString());

        ClickEvent click = name.getStyle().getClickEvent();
        require(click != null, "click event missing on " + expectedText);
        require(click.getAction() == ClickEvent.Action.RUN_COMMAND,
                "unexpected click action: " + click.getAction());
        require(AreaRecallPacket.openCommand(uuid).equals(click.getValue()),
                "click command mismatch: " + click.getValue() + " for " + expectedText);
        require(click.getValue().contains(uuid.toString()),
                "click command does not carry the pet uuid: " + click.getValue());

        // 可点性靠绿色 + 下划线表达（不用 HoverEvent，见 clickableName 的注释）。
        require(name.getStyle().isUnderlined(),
                "clickable name is not underlined: " + expectedText);
        require(name.getStyle().getColor() != null
                        && name.getStyle().getColor().getValue() == ChatFormatting.GREEN.getColor(),
                "clickable name is not green: " + name.getStyle().getColor());
    }

    /** 快照里记有 TBF_OwnerUUID 时，必须解析出该主人（这是「只收回自己的宠物」的判据）。 */
    private static void testSnapshotOwnerFromTbfTag() {
        UUID owner = UUID.randomUUID();
        CompoundTag nbt = new CompoundTag();
        TbfOwnerTag.write(nbt, owner);
        UUID resolved = trulybestfriends.getSnapshotOwnerUUID(nbt);
        require(owner.equals(resolved),
                "snapshot owner was not read from TBF_OwnerUUID: " + resolved);

        // 无任何归属字段 → 解析为 null（调用方据此退回「文件在谁目录就归谁」）。
        require(trulybestfriends.getSnapshotOwnerUUID(new CompoundTag()) == null,
                "empty snapshot should resolve to a null owner");
    }

    private static AreaRecallPacket.RecalledPet pet(UUID uuid, String name) {
        return new AreaRecallPacket.RecalledPet(uuid, Component.literal(name));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
