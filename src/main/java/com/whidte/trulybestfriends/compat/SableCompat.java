package com.whidte.trulybestfriends.compat;

import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Sable SubLevel 兼容层。
 *
 * <p>Sable 引入了 <b>SubLevel</b> 系统：在同一维度内嵌入独立的物理结构（地块），
 * 拥有独立的局部坐标系。这导致两个问题：</p>
 *
 * <ol>
 *   <li><b>坐标空间不同</b>：SubLevel 内实体的 {@code Pos} 是局部坐标，
 *       直接传给 {@code player.teleportTo} 会把玩家传到父维度的错误位置。</li>
 *   <li><b>追踪状态</b>：玩家需要"追踪"（track）SubLevel 才能看到其内部内容，
 *       仅靠 {@code teleportTo} 无法同步此状态到客户端。</li>
 * </ol>
 *
 * <p>本类封装了与 Sable 交互所需的全部反射逻辑，参考 WaystonesSable 的实现方式，
 * 提供 SubLevel 检测、坐标变换、追踪同步等构建块。Sable 未安装时所有方法
 * 静默返回安全默认值，不影响模组其余功能。</p>
 *
 * <h3>典型使用流程</h3>
 * <ol>
 *   <li><b>保存宠物时</b>（服务端）：调用 {@link #captureSubLevelInfo} 将 SubLevel UUID
 *       写入宠物 NBT（若宠物在 SubLevel 内）。</li>
 *   <li><b>传送到宠物时</b>（服务端）：从 NBT 读取 SubLevel UUID，调用
 *       {@link #projectToWorld} 将局部坐标变换为世界坐标，再 {@code teleportTo}。</li>
 *   <li><b>传送后同步</b>（客户端）：调用 {@link #applyClientTracking} 设置追踪状态，
 *       防止 rubber-banding。</li>
 * </ol>
 *
 * <p>所有 Sable 类引用均通过 {@link Class#forName} + 反射访问，因为 Sable 不是
 * 编译期依赖。模式与 {@link IafCompat} 一致。</p>
 */
public final class SableCompat {

	private SableCompat() {}

	/** Sable 的 mod id。 */
	private static final String MOD_ID = "sable";

	/** 宠物 NBT 中存储 SubLevel UUID 的键名。 */
	public static final String SUBLEVEL_ID_KEY = "SableSubLevelId";

	// ─── 反射状态 ───

	private static volatile boolean initialized;
	private static boolean loaded;

	/** SableCompanion.INSTANCE（单例）。 */
	private static Object sableCompanionInstance;

	/** SableCompanion.getContaining(Level, Position) → SubLevelAccess */
	private static Method getContainingMethod;

	/** SableCompanion.projectOutOfSubLevel(Level, Position) → Vec3 */
	private static Method projectOutOfSubLevelMethod;

	/** SubLevelAccess.getUniqueId() → UUID */
	private static Method subLevelGetUniqueIdMethod;

	/** SubLevelContainer.getContainer(Level) → SubLevelContainer（静态） */
	private static Method containerGetContainerMethod;

	/** SubLevelContainer.getSubLevel(UUID) → SubLevel */
	private static Method containerGetSubLevelMethod;

	/** EntitySubLevelUtil.setOldPosNoMovement(Entity)（静态） */
	private static Method setOldPosNoMovementMethod;

	/** EntityMovementExtension mixinterface（Sable 通过 mixin 注入到 Entity）。 */
	private static Class<?> entityMovementExtensionClass;
	/** EntityMovementExtension.sable$setTrackingSubLevel(SubLevel) */
	private static Method setTrackingSubLevelMethod;

	/** EntityStickExtension mixinterface。 */
	private static Class<?> entityStickExtensionClass;
	/** EntityStickExtension.sable$setPlotPosition(Object) */
	private static Method setPlotPositionMethod;

	// ─── 初始化 ───

	private static void init() {
		if (initialized) return;
		synchronized (SableCompat.class) {
			if (initialized) return;
			initialized = true;
			if (!ModList.get().isLoaded(MOD_ID)) {
				loaded = false;
				return;
			}
			try {
				// SableCompanion.INSTANCE
				Class<?> sableCompanionClass = Class.forName(
						"dev.ryanhcode.sable.companion.SableCompanion");
				Field instanceField = sableCompanionClass.getField("INSTANCE");
				sableCompanionInstance = instanceField.get(null);

				getContainingMethod = findMethod(
						sableCompanionClass, "getContaining", Level.class, Vec3.class);
				projectOutOfSubLevelMethod = findMethod(
						sableCompanionClass, "projectOutOfSubLevel", Level.class, Vec3.class);

				// SubLevelAccess.getUniqueId()
				Class<?> subLevelAccessClass = Class.forName(
						"dev.ryanhcode.sable.companion.SubLevelAccess");
				subLevelGetUniqueIdMethod = subLevelAccessClass.getMethod("getUniqueId");

				// SubLevelContainer.getContainer(Level) + getSubLevel(UUID)
				Class<?> subLevelContainerClass = Class.forName(
						"dev.ryanhcode.sable.api.sublevel.SubLevelContainer");
				containerGetContainerMethod = findMethod(
						subLevelContainerClass, "getContainer", Level.class);
				containerGetSubLevelMethod = resolveGetSubLevel(subLevelContainerClass);

				// EntitySubLevelUtil.setOldPosNoMovement(Entity)
				Class<?> entitySubLevelUtilClass = Class.forName(
						"dev.ryanhcode.sable.api.entity.EntitySubLevelUtil");
				setOldPosNoMovementMethod = entitySubLevelUtilClass.getMethod(
						"setOldPosNoMovement", Entity.class);

				// EntityMovementExtension（mixinterface，方法名含 $）
				entityMovementExtensionClass = Class.forName(
						"dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension");
				setTrackingSubLevelMethod = findMethodByName(
						entityMovementExtensionClass, "sable$setTrackingSubLevel", 1);

				// EntityStickExtension（mixinterface）
				entityStickExtensionClass = Class.forName(
						"dev.ryanhcode.sable.mixinterface.entity.entities_stick_sublevels.EntityStickExtension");
				setPlotPositionMethod = findMethodByName(
						entityStickExtensionClass, "sable$setPlotPosition", 1);

				loaded = true;
				trulybestfriends.LOGGER.info(
						"SableCompat: Sable detected, SubLevel compatibility enabled.");
			} catch (Exception e) {
				loaded = false;
				trulybestfriends.LOGGER.warn(
						"SableCompat: Sable present but API unavailable, compatibility disabled: {}",
						e.toString());
			}
		}
	}

	/** getSubLevel 可能在 SubLevelContainer 或其子类 ServerSubLevelContainer 上定义。 */
	private static Method resolveGetSubLevel(Class<?> containerClass) throws ClassNotFoundException, NoSuchMethodException {
		try {
			return containerClass.getMethod("getSubLevel", UUID.class);
		} catch (NoSuchMethodException e) {
			Class<?> serverClass = Class.forName(
					"dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer");
			return serverClass.getMethod("getSubLevel", UUID.class);
		}
	}

	// ─── 公开 API ───

	/** Sable 是否已安装且 API 可用。 */
	public static boolean isLoaded() {
		init();
		return loaded;
	}

	// === 检测 ===

	/**
	 * 返回包含指定坐标的 SubLevel 的 UUID，若坐标不在任何 SubLevel 内则返回 null。
	 *
	 * @param level 宠物所在的父维度
	 * @param pos   宠物的坐标（SubLevel 内为局部坐标）
	 * @return SubLevel UUID，或 null
	 */
	public static UUID getSubLevelId(ServerLevel level, Vec3 pos) {
		if (!isLoaded() || level == null || pos == null) return null;
		try {
			Object subLevelAccess = getContainingMethod.invoke(sableCompanionInstance, level, pos);
			if (subLevelAccess == null) return null;
			Object uuid = subLevelGetUniqueIdMethod.invoke(subLevelAccess);
			return (UUID) uuid;
		} catch (Exception e) {
			trulybestfriends.LOGGER.debug("SableCompat.getSubLevelId failed: {}", e.toString());
			return null;
		}
	}

	// === 坐标变换 ===

	/**
	 * 将 SubLevel 局部坐标投影到父维度的世界坐标。
	 *
	 * <p>对应 Sable 的 {@code SableCompanion.INSTANCE.projectOutOfSubLevel(level, pos)}。
	 * 若坐标不在 SubLevel 内，返回 null。</p>
	 *
	 * @param level     父维度
	 * @param localPos  SubLevel 内的局部坐标
	 * @return 世界坐标，或 null（Sable 未加载 / 投影失败）
	 */
	public static Vec3 projectToWorld(Level level, Vec3 localPos) {
		if (!isLoaded() || level == null || localPos == null) return null;
		try {
			Object result = projectOutOfSubLevelMethod.invoke(sableCompanionInstance, level, localPos);
			return (Vec3) result;
		} catch (Exception e) {
			trulybestfriends.LOGGER.debug("SableCompat.projectToWorld failed: {}", e.toString());
			return null;
		}
	}

	// === SubLevel 解析 ===

	/**
	 * 通过 UUID 在指定维度中解析 SubLevel 对象。
	 *
	 * <p>对应 Sable 的 {@code SubLevelContainer.getContainer(level).getSubLevel(uuid)}。
	 * 客户端和服务端均可调用（使用通用的 SubLevelContainer）。</p>
	 *
	 * @param level      父维度
	 * @param subLevelId SubLevel UUID
	 * @return SubLevel 对象（Object 类型，调用者无需关心具体类），或 null
	 */
	public static Object resolveSubLevel(Level level, UUID subLevelId) {
		if (!isLoaded() || level == null || subLevelId == null) return null;
		try {
			Object container = containerGetContainerMethod.invoke(null, level);
			if (container == null) return null;
			return containerGetSubLevelMethod.invoke(container, subLevelId);
		} catch (Exception e) {
			trulybestfriends.LOGGER.debug("SableCompat.resolveSubLevel failed: {}", e.toString());
			return null;
		}
	}

	// === 客户端追踪同步 ===

	/**
	 * 在客户端应用 SubLevel 追踪状态（传送后调用）。
	 *
	 * <p>镜像 WaystonesSable 的 {@code SableTeleportPayload.handle} 逻辑：</p>
	 * <ol>
	 *   <li>{@code player.moveTo(worldX, worldY, worldZ)} — 设置世界坐标位置</li>
	 *   <li>若 subLevelId 非空：解析 SubLevel 并调用
	 *       {@code sable$setTrackingSubLevel(subLevel)} 让玩家"进入"SubLevel</li>
	 *   <li>若 subLevelId 为空：清除追踪和 plot 位置</li>
	 *   <li>调用 {@code setOldPosNoMovement} 防止 rubber-banding</li>
	 * </ol>
	 *
	 * @param entity     玩家实体（客户端）
	 * @param subLevelId 目标 SubLevel UUID，null 表示传送到普通空间
	 * @param worldX     世界坐标 X（已变换）
	 * @param worldY     世界坐标 Y
	 * @param worldZ     世界坐标 Z
	 * @return 成功应用追踪返回 true；Sable 未加载或 SubLevel 不可用返回 false
	 */
	public static boolean applyClientTracking(Entity entity, UUID subLevelId,
	                                          double worldX, double worldY, double worldZ) {
		if (!isLoaded() || entity == null) return false;
		try {
			entity.moveTo(worldX, worldY, worldZ);
			if (subLevelId != null) {
				Object subLevel = resolveSubLevel(entity.level(), subLevelId);
				if (subLevel != null) {
					setTrackingSubLevel(entity, subLevel);
					setOldPosNoMovement(entity);
					return true;
				}
			} else {
				setTrackingSubLevel(entity, null);
			}
			clearPlotPosition(entity);
			setOldPosNoMovement(entity);
			return true;
		} catch (Exception e) {
			trulybestfriends.LOGGER.warn("SableCompat.applyClientTracking failed: {}", e.toString());
			return false;
		}
	}

	/**
	 * 设置或清除实体的 SubLevel 追踪状态。
	 *
	 * <p>对应 Sable mixinterface 方法
	 * {@code EntityMovementExtension.sable$setTrackingSubLevel(SubLevel)}。
	 * 传 null 清除追踪（玩家离开 SubLevel）。</p>
	 *
	 * @param entity  目标实体（通常是玩家）
	 * @param subLevel SubLevel 对象（来自 {@link #resolveSubLevel}），或 null
	 * @return 成功返回 true
	 */
	public static boolean setTrackingSubLevel(Entity entity, Object subLevel) {
		if (!isLoaded() || entity == null) return false;
		try {
			setTrackingSubLevelMethod.invoke(entity, subLevel);
			return true;
		} catch (Exception e) {
			trulybestfriends.LOGGER.debug("SableCompat.setTrackingSubLevel failed: {}", e.toString());
			return false;
		}
	}

	/**
	 * 清除实体的 plot 位置（玩家离开 SubLevel 时调用）。
	 *
	 * <p>对应 Sable mixinterface 方法
	 * {@code EntityStickExtension.sable$setPlotPosition(null)}。</p>
	 *
	 * @param entity 目标实体
	 * @return 成功返回 true
	 */
	public static boolean clearPlotPosition(Entity entity) {
		if (!isLoaded() || entity == null) return false;
		try {
			setPlotPositionMethod.invoke(entity, (Object) null);
			return true;
		} catch (Exception e) {
			trulybestfriends.LOGGER.debug("SableCompat.clearPlotPosition failed: {}", e.toString());
			return false;
		}
	}

	/**
	 * 防止传送后的 rubber-banding。
	 *
	 * <p>对应 Sable 的 {@code EntitySubLevelUtil.setOldPosNoMovement(entity)}，
	 * 将实体的 oldPosition 同步到当前位置，避免客户端插值产生回弹。</p>
	 *
	 * @param entity 目标实体
	 * @return 成功返回 true
	 */
	public static boolean setOldPosNoMovement(Entity entity) {
		if (!isLoaded() || entity == null) return false;
		try {
			setOldPosNoMovementMethod.invoke(null, entity);
			return true;
		} catch (Exception e) {
			trulybestfriends.LOGGER.debug("SableCompat.setOldPosNoMovement failed: {}", e.toString());
			return false;
		}
	}

	// === NBT 辅助 ===

	/**
	 * 若实体在 SubLevel 内，将其 SubLevel UUID 写入 NBT；否则清除旧数据。
	 *
	 * <p>应在 {@code PetEntitySnapshot.capture} / {@code RequestPetDataPacket.toClientNbt}
	 * 中调用。NBT 中的 {@code Pos} 保持实体原始坐标（SubLevel 内为局部坐标），
	 * SubLevel UUID 单独存储在 {@link #SUBLEVEL_ID_KEY} 键下。</p>
	 *
	 * @param nbt       待写入的 NBT
	 * @param level     实体所在维度
	 * @param entityPos 实体坐标
	 * @return 写入了 SubLevel UUID 返回 true（表示实体在 SubLevel 内）
	 */
	public static boolean captureSubLevelInfo(CompoundTag nbt, ServerLevel level, Vec3 entityPos) {
		if (!isLoaded()) {
			removeSubLevelInfo(nbt);
			return false;
		}
		UUID subLevelId = getSubLevelId(level, entityPos);
		if (subLevelId != null) {
			nbt.putUUID(SUBLEVEL_ID_KEY, subLevelId);
			return true;
		}
		removeSubLevelInfo(nbt);
		return false;
	}

	/**
	 * 从宠物 NBT 中读取 SubLevel UUID。
	 *
	 * @return SubLevel UUID，或 null（不在 SubLevel 内 / Sable 未加载）
	 */
	public static UUID readSubLevelId(CompoundTag nbt) {
		if (nbt == null || !nbt.hasUUID(SUBLEVEL_ID_KEY)) return null;
		return nbt.getUUID(SUBLEVEL_ID_KEY);
	}

	/** 从宠物 NBT 中移除 SubLevel 信息。 */
	public static void removeSubLevelInfo(CompoundTag nbt) {
		if (nbt != null && nbt.contains(SUBLEVEL_ID_KEY)) {
			nbt.remove(SUBLEVEL_ID_KEY);
		}
	}

	// ─── 反射辅助 ───

	/**
	 * 按名称和参数兼容性查找公开方法。
	 * 匹配条件：方法名相同，且每个声明参数类型 {@code isAssignableFrom(给定参数类型)}。
	 * 例如方法声明 {@code (Level, Position)}，搜索 {@code (Level, Vec3)} 可匹配，
	 * 因为 {@code Position.isAssignableFrom(Vec3) == true}。
	 */
	private static Method findMethod(Class<?> clazz, String name, Class<?>... argTypes) {
		for (Method m : clazz.getMethods()) {
			if (!m.getName().equals(name)) continue;
			Class<?>[] paramTypes = m.getParameterTypes();
			if (paramTypes.length != argTypes.length) continue;
			boolean match = true;
			for (int i = 0; i < argTypes.length; i++) {
				if (!paramTypes[i].isAssignableFrom(argTypes[i])) {
					match = false;
					break;
				}
			}
			if (match) return m;
		}
		return null;
	}

	/** 按名称和参数数量查找方法（用于参数类型未知的 mixinterface 方法）。 */
	private static Method findMethodByName(Class<?> clazz, String name, int paramCount) {
		for (Method m : clazz.getMethods()) {
			if (m.getName().equals(name) && m.getParameterCount() == paramCount) {
				return m;
			}
		}
		return null;
	}
}
