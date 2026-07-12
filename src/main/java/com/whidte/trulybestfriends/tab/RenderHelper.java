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
import net.minecraftforge.registries.ForgeRegistries;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Shared low-level rendering helpers used by multiple tab UI classes. */
final class RenderHelper {

	private RenderHelper() {}

	/**
	 * Render an entity in the GUI with float-precision scale, replicating
	 * {@link net.minecraft.client.gui.screens.inventory.InventoryScreen#renderEntityInInventory}
	 * but accepting a float scale instead of int.  The vanilla method casts
	 * the int scale to float internally, so this version simply skips the
	 * truncation.
	 */
	static void renderEntityInInventory(GuiGraphics g, int x, int y, float scale,
	                                     Quaternionf pose, Quaternionf cameraOrientation,
	                                     LivingEntity entity) {
		g.pose().pushPose();
		g.pose().translate((double) x, (double) y, 50.0);
		g.pose().mulPoseMatrix(new Matrix4f().scaling(scale, scale, -scale));
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

	// ------------------------------------------------------------------
	//  Multipart entity Y-facing auto-detection
	// ------------------------------------------------------------------

	private static final java.util.Map<String, Float> MULTIPART_Y_BASE_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * Auto-detect the Y-axis base rotation (in radians) for a multipart
	 * entity by examining its model's head/neck part position.
	 * <p>
	 * Minecraft's model convention: -Z is "forward" (where the head is).
	 * Standard models (e.g. Ender Dragon) have head at -Z → return 0.
	 * Non-standard models (e.g. Ice &amp; Fire dragons) have head at +Z →
	 * return PI so the model is flipped to face the camera.
	 * <p>
	 * Results are cached per entity type id.
	 */
	static float detectMultipartYBase(LivingEntity entity) {
		String typeKey = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType()).toString();
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
			for (String name : new String[]{"head", "neck", "head1", "neck1", "skull", "jaw"}) {
				Float z = findPartLocalZ(root, name);
				if (z != null) return z;
			}
		}
		for (String name : new String[]{"head", "neck", "head1", "neck1", "skull", "jaw"}) {
			Float z = getModelPartFieldZ(model, name);
			if (z != null) return z;
		}
		return null;
	}

	/** Access the protected {@code root} field of Model via reflection. */
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
	 * For renderers that don't extend LivingEntityRenderer (e.g.
	 * EnderDragonRenderer), access the private "model" field via reflection.
	 */
	private static EntityModel<?> getModelFromRenderer(EntityRenderer<?> renderer) {
		Class<?> cls = renderer.getClass();
		while (cls != null && cls != Object.class) {
			try {
				java.lang.reflect.Field f = cls.getDeclaredField("model");
				f.setAccessible(true);
				Object value = f.get(renderer);
				if (value instanceof EntityModel<?> entityModel) return entityModel;
			} catch (NoSuchFieldException ignored) {
				// try superclass
			} catch (Exception ignored) {
				break;
			}
			cls = cls.getSuperclass();
		}
		return null;
	}

	private static Float getModelPartFieldZ(EntityModel<?> model, String name) {
		Class<?> cls = model.getClass();
		while (cls != null && cls != Object.class) {
			try {
				java.lang.reflect.Field f = cls.getDeclaredField(name);
				f.setAccessible(true);
				Object value = f.get(model);
				if (value instanceof ModelPart part) return part.z;
			} catch (NoSuchFieldException ignored) {
				// try superclass
			} catch (Exception ignored) {
				break;
			}
			cls = cls.getSuperclass();
		}
		return null;
	}

	/**
	 * Search the model hierarchy for a child named {@code name} and return
	 * its local Z offset (in model units, already divided by 16).
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

	/** Tile a texture horizontally with a repeating source region, respecting destination bounds. */
	static void tileBlitH(GuiGraphics g, ResourceLocation tex, int x, int y, int drawW, int drawH,
	                      int u, int v, int srcW, int srcH, int texW, int texH) {
		int drawn = 0;
		while (drawn < drawW) {
			int chunk = Math.min(srcW, drawW - drawn);
			g.blit(tex, x + drawn, y, chunk, drawH, u, v, chunk, srcH, texW, texH);
			drawn += chunk;
		}
	}

	/** Draw a Component at (x, y) using direct batch rendering. */
	static void drawString(GuiGraphics g, Font font, Component text, int x, int y, int color) {
		font.drawInBatch(text.getVisualOrderText(), x, y, color, false,
				g.pose().last().pose(), g.bufferSource(),
				Font.DisplayMode.NORMAL, 0, 15728880);
	}

	/** Draw a Component with horizontal scrolling when its width exceeds maxWidth. */
	static void drawScrollingString(GuiGraphics g, Font font, Component text, int x, int y, int maxWidth, int color) {
		int textWidth = font.width(text);
		if (textWidth <= maxWidth) {
			drawString(g, font, text, x, y, color);
			return;
		}
		long time = System.currentTimeMillis();
		int overflow = textWidth - maxWidth + 12;
		int cycleMs = 8000;
		long phase = time % cycleMs;

		int scrollOffset;
		if (phase < 2000) {
			scrollOffset = 0;
		} else if (phase < 6000) {
			scrollOffset = (int) (overflow * (phase - 2000) / 4000f);
		} else {
			scrollOffset = overflow;
		}
		drawString(g, font, text, x - scrollOffset, y, color);
	}
}
