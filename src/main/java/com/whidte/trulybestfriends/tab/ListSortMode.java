package com.whidte.trulybestfriends.tab;

import net.minecraft.network.chat.Component;

/**
 * 宠物列表可选的排序依据。
 *
 * <p>每个枚举项的翻译键同时充当排序依据选择器的取值标识，
 * 因此列表里的字符串不需要再做一层到枚举的映射。</p>
 */
enum ListSortMode {
	/** 按优先级数值排序：数值越小表示优先级越高，越靠前。 */
	PRIORITY("trulybestfriends.sort.mode.priority"),
	/** 按宠物注册进模组的时间排序：缺失时间戳的旧宠物按“最早”处理。 */
	REGISTERED_AT("trulybestfriends.sort.mode.registered"),
	/** 按当前生命值占最大生命值的比例排序：比例越低表示越需要治疗，越靠前。 */
	HEALTH("trulybestfriends.sort.mode.health");

	/** 该排序依据的翻译键，同时也是选择器中的取值标识。 */
	private final String key;

	ListSortMode(String key) {
		this.key = key;
	}

	/** 选择器使用的取值标识。 */
	String key() {
		return key;
	}

	/** 该排序依据在界面中显示的名称。 */
	Component label() {
		return Component.translatable(key);
	}

	/** 按取值标识查找枚举项；无法识别时回退到按优先级排序。 */
	static ListSortMode byKey(String key) {
		for (ListSortMode mode : values()) {
			if (mode.key.equals(key)) return mode;
		}
		return PRIORITY;
	}
}
