package com.whidte.trulybestfriends.tab;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** Shared low-level rendering helpers used by multiple tab UI classes. */
final class RenderHelper {

	private RenderHelper() {}

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
