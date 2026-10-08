package com.whidte.trulybestfriends;

import net.minecraft.resources.ResourceLocation;

import java.util.Set;

/**
 * 实体类型名单（自动注册黑名单 / 不可复活白名单 / 死亡清除白名单）的统一匹配规则。
 *
 * <p>名单条目有两种写法：</p>
 * <ul>
 *     <li>实体类型 id，例如 {@code minecraft:wolf}——只命中该类型；</li>
 *     <li>命名空间通配符，例如 {@code some_mod:*}——命中该命名空间下的所有实体类型。</li>
 * </ul>
 *
 * <p>三级名单共用同一份实现，避免「黑名单会通配、白名单不会」这种不一致。</p>
 *
 * <p>只做纯字符串匹配，不触碰任何需要 bootstrap 的注册表，所以可以在裸 JVM 冒烟测试里直接验证
 * （见 {@code EntityTypeListMatcherSmokeTest}）。</p>
 */
final class EntityTypeListMatcher {

    /** 命名空间通配条目的后缀，例如 {@code some_mod:*} 里的 {@code :*}。 */
    static final String WILDCARD_SUFFIX = ":*";

    private EntityTypeListMatcher() {}

    /**
     * 判断某个实体类型 id 是否命中名单。
     *
     * <p>命中条件：与条目完全相等，或名单中存在「该 id 所属命名空间的通配条目」。
     * 所有比较都是字面量比较；只有当 id 本身是合法的 {@link ResourceLocation}
     * （命名空间只允许小写）时才有可能命中通配条目。</p>
     *
     * @param list         名单条目集合，允许为 {@code null}（视为空名单）
     * @param entityTypeId 待判断的实体类型 id，例如 {@code irons_spellbooks:spectral_steed}
     * @return 命中返回 true
     */
    static boolean matches(Set<String> list, String entityTypeId) {
        if (list == null || list.isEmpty() || entityTypeId == null || entityTypeId.isEmpty()) return false;
        if (list.contains(entityTypeId)) return true;

        String namespace = namespaceOf(entityTypeId);
        return namespace != null && list.contains(namespace + WILDCARD_SUFFIX);
    }

    /**
     * 实体类型 id 的命名空间。
     * id 不是合法的 {@link ResourceLocation} 时返回 {@code null}——此时只能精确匹配。
     */
    static String namespaceOf(String entityTypeId) {
        ResourceLocation id = ResourceLocation.tryParse(entityTypeId);
        return id == null ? null : id.getNamespace();
    }
}
