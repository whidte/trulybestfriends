package com.whidte.trulybestfriends.tab;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import static com.whidte.trulybestfriends.tab.TrulyConstants.DEFAULT_ROT_X;
import static com.whidte.trulybestfriends.tab.TrulyConstants.DEFAULT_ROT_Y;

/** 多个标签页界面类共用的底层渲染辅助方法。 */
final class RenderHelper {
	private static final String[] FACING_PART_NAMES = {"head", "neck", "head1", "neck1", "skull", "jaw"};

	private RenderHelper() {}

	static boolean isMultipartPreview(LivingEntity entity) {
		return entity.getScale() > 1.0001f
				|| (entity.getParts() != null && entity.getParts().length > 0);
	}

	static void applyPreviewRotation(LivingEntity entity, boolean multipart,
	                                 float horizontalRotation, float verticalRotation,
	                                 boolean unclampedPitch) {
		if (multipart) {
			entity.yBodyRot = entity.yBodyRotO = 0f;
			entity.setYRot(0f);
			entity.yRotO = entity.yHeadRot = entity.yHeadRotO = 0f;
			return;
		}

		entity.yBodyRot = 180f + horizontalRotation * 20f;
		entity.setYRot(180f + horizontalRotation * 40f);
		float pitch = -verticalRotation * 20f;
		if (unclampedPitch) TrulyConstants.setXRotUnclamped(entity, pitch);
		else entity.setXRot(pitch);
		entity.yHeadRot = entity.yHeadRotO = entity.yBodyRot;
	}

	/**
	 * 在界面中以浮点精度缩放渲染实体，复刻
	 * {@link net.minecraft.client.gui.screens.inventory.InventoryScreen#renderEntityInInventory}
	 * 但接受 float 缩放值而非 int。原版方法内部会把 int 缩放值转为
	 * float，因此本版本只是省去了截断。
	 */
	static void renderEntityInInventory(GuiGraphics g, int x, int y, float scale,
	                                     Quaternionf pose, Quaternionf cameraOrientation,
	                                     LivingEntity entity) {
		g.pose().pushPose();
		g.pose().translate((double) x, (double) y, 50.0);
		g.pose().scale(scale, scale, -scale);
		g.pose().mulPose(pose);
		Lighting.setupForEntityInInventory();
		EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
		if (cameraOrientation != null) {
			dispatcher.overrideCameraOrientation(new Quaternionf(cameraOrientation).conjugate());
		}
		dispatcher.setRenderShadow(false);
		RenderSystem.runAsFancy(() ->
				dispatcher.render(entity, 0.0, 0.0, 0.0, 0.0f, 1.0f,
						g.pose(), g.bufferSource(), 15728880));
		g.flush();
		dispatcher.setRenderShadow(true);
		g.pose().popPose();
		Lighting.setupFor3DItems();
	}

	/**
	 * 渲染小型宠物预览（编队槽位 / 拖动残影），使用与宠物列表条目相同的
	 * 姿态逻辑，并以实体脚部锚定在 (x, y) 处。
	 */
	static void renderMiniPet(GuiGraphics g, int x, int y, float baseSize, LivingEntity pet) {
		float scale = TrulyScreen.computePreviewScale(pet, baseSize);
		boolean multipart = isMultipartPreview(pet);
		Quaternionf quat;
		Quaternionf quatPitch;
		if (multipart) {
			float pitch = multipartPitchRadians(DEFAULT_ROT_Y);
			quat = buildMultipartPose(
					detectMultipartYBase(pet) - DEFAULT_ROT_X * 20.0f * ((float) Math.PI / 180f),
					pitch);
			quatPitch = new Quaternionf().rotateX(-pitch);
		} else {
			quat = new Quaternionf().rotateZ((float) Math.PI)
					.rotateX(DEFAULT_ROT_Y * 20.0f * ((float) Math.PI / 180f));
			quatPitch = new Quaternionf().rotateX(DEFAULT_ROT_Y * 20.0f * ((float) Math.PI / 180f));
		}

		applyPreviewRotation(pet, multipart, DEFAULT_ROT_X, DEFAULT_ROT_Y, true);

		renderEntityInInventory(g, x, y, scale, quat, quatPitch, pet);
	}

	/**
	 * 构建多部件预览姿态，俯仰角位于视图空间。俯仰角必须先于偏航角合成，
	 * 这样垂直拖动在每个水平视角下都能保持相同的屏幕空间轴向。
	 */
	static Quaternionf buildMultipartPose(float yawRadians, float pitchRadians) {
		return new Quaternionf()
				.rotateZ((float) Math.PI)
				.rotateX(pitchRadians)
				.rotateY(yawRadians);
	}

	static float multipartPitchRadians(float verticalRotation) {
		return verticalRotation * 20.0f * ((float) Math.PI / 180.0f);
	}

	// ------------------------------------------------------------------
	//  多部件实体 Y 朝向自动检测
	// ------------------------------------------------------------------

	private static final java.util.Map<String, Float> MULTIPART_Y_BASE_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * 通过检查多部件实体模型的头部/颈部部件位置，自动检测其 Y 轴基础
	 * 旋转（单位为弧度）。
	 * <p>
	 * Minecraft 的模型约定：-Z 为“前方”（头部所在方向）。
	 * 标准模型（例如末影龙）头部在 -Z → 返回 0。
	 * 非标准模型（例如 Ice &amp; Fire 的龙）头部在 +Z →
	 * 返回 PI，使模型翻转以面向摄像机。
	 * <p>
	 * 结果按实体类型 id 缓存。
	 */
	static float detectMultipartYBase(LivingEntity entity) {
		String typeKey = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
		Float cached = MULTIPART_Y_BASE_CACHE.get(typeKey);
		if (cached != null) return cached;

		float result = detectMultipartYBaseUncached(entity);
		MULTIPART_Y_BASE_CACHE.put(typeKey, result);
		return result;
	}

	@SuppressWarnings("rawtypes")
	private static float detectMultipartYBaseUncached(LivingEntity entity) {
		try {
			EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
			EntityRenderer<?> renderer = dispatcher.getRenderer(entity);
			EntityModel model;
			if (renderer instanceof LivingEntityRenderer livingRenderer) {
				model = livingRenderer.getModel();
			} else {
				model = getModelFromRenderer(renderer);
			}
			if (model != null) {
				Float z = findFacingPartZ(model);
				if (z != null && z <= 0) return 0f;
			}
		} catch (Exception ignored) {}
		return (float) Math.PI;
	}

	private static Float findFacingPartZ(EntityModel<?> model) {
		ModelPart root = getModelRoot(model);
		if (root != null) {
			for (String name : FACING_PART_NAMES) {
				Float z = findPartLocalZ(root, name);
				if (z != null) return z;
			}
		}
		for (String name : FACING_PART_NAMES) {
			Float z = getModelPartFieldZ(model, name);
			if (z != null) return z;
		}
		return null;
	}

	/** 通过反射访问 Model 的 protected 字段 {@code root}。 */
	private static ModelPart getModelRoot(EntityModel<?> model) {
		try {
			java.lang.reflect.Field f = net.minecraft.client.model.Model.class.getDeclaredField("root");
			f.setAccessible(true);
			return (ModelPart) f.get(model);
		} catch (Exception ignored) {
			return null;
		}
	}

	/**
	 * 对于未继承 LivingEntityRenderer 的渲染器（例如
	 * EnderDragonRenderer），通过反射访问其私有的 "model" 字段。
	 */
	private static EntityModel<?> getModelFromRenderer(EntityRenderer<?> renderer) {
		return findFieldValue(renderer, "model", EntityModel.class);
	}

	private static Float getModelPartFieldZ(EntityModel<?> model, String name) {
		ModelPart part = findFieldValue(model, name, ModelPart.class);
		return part != null ? part.z : null;
	}

	private static <T> T findFieldValue(Object target, String name, Class<T> valueType) {
		Class<?> cls = target.getClass();
		while (cls != null && cls != Object.class) {
			try {
				java.lang.reflect.Field f = cls.getDeclaredField(name);
				f.setAccessible(true);
				Object value = f.get(target);
				return valueType.isInstance(value) ? valueType.cast(value) : null;
			} catch (NoSuchFieldException ignored) {
			} catch (Exception ignored) {
				return null;
			}
			cls = cls.getSuperclass();
		}
		return null;
	}

	/**
	 * 在模型层级中查找名为 {@code name} 的子部件，并返回
	 * 其局部 Z 偏移（单位为模型单位，已除以 16）。
	 */
	@SuppressWarnings("unchecked")
	private static Float findPartLocalZ(ModelPart root, String name) {
		if (root.hasChild(name)) {
			ModelPart child = root.getChild(name);
			PoseStack ps = new PoseStack();
			child.translateAndRotate(ps);
			Vector3f pos = new Vector3f();
			ps.last().pose().getTranslation(pos);
			return pos.z();
		}
		try {
			java.lang.reflect.Field f = ModelPart.class.getDeclaredField("children");
			f.setAccessible(true);
			java.util.Map<String, ModelPart> children = (java.util.Map<String, ModelPart>) f.get(root);
			for (ModelPart child : children.values()) {
				Float z = findPartLocalZ(child, name);
				if (z != null) return z;
			}
		} catch (Exception ignored) {}
		return null;
	}

	/** 使用重复的源区域沿水平方向平铺纹理，并遵循目标区域边界。 */
	static void tileBlitH(GuiGraphics g, ResourceLocation tex, int x, int y, int drawW, int drawH,
	                      int u, int v, int srcW, int srcH, int texW, int texH) {
		int drawn = 0;
		while (drawn < drawW) {
			int chunk = Math.min(srcW, drawW - drawn);
			g.blit(tex, x + drawn, y, chunk, drawH, u, v, chunk, srcH, texW, texH);
			drawn += chunk;
		}
	}

	/** 使用直接批渲染在 (x, y) 处绘制一个 Component。 */
	static void drawString(GuiGraphics g, Font font, Component text, int x, int y, int color) {
		font.drawInBatch(text.getVisualOrderText(), x, y, color, false,
				g.pose().last().pose(), g.bufferSource(),
				Font.DisplayMode.NORMAL, 0, 15728880);
	}

	/** 绘制一个 Component，当其宽度超过 maxWidth 时水平滚动。 */
	static void drawScrollingString(GuiGraphics g, Font font, Component text, int x, int y, int maxWidth, int color) {
		int textWidth = font.width(text);
		if (textWidth <= maxWidth) {
			drawString(g, font, text, x, y, color);
			return;
		}
		int scrollOffset = scrollingOffset(textWidth - maxWidth + 12);
		drawString(g, font, text, x - scrollOffset, y, color);
	}

	static int scrollingOffset(int overflow) {
		long phase = System.currentTimeMillis() % 8000;
		return phase < 2000 ? 0
				: phase < 6000 ? (int) (overflow * (phase - 2000) / 4000f)
				: overflow;
	}
}
