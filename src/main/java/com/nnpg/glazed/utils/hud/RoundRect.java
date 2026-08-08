package com.nnpg.glazed.utils.hud;

import net.minecraft.client.gui.GuiGraphics;

public final class RoundRect {

    private RoundRect() {}

    public static void draw(GuiGraphics context, int x, int y, int width, int height, int radius, int argb) {
        if (width <= 0 || height <= 0) return;

        int r = Math.max(0, Math.min(radius, Math.min(width, height) / 2));
        if (r == 0) {
            context.fill(x, y, x + width, y + height, argb);
            return;
        }

        context.fill(x, y + r, x + width, y + height - r, argb);

        for (int row = 0; row < r; row++) {
            double dy = r - row - 0.5;
            int inset = r - (int) Math.round(Math.sqrt(Math.max(0.0, r * r - dy * dy)));

            context.fill(x + inset, y + row, x + width - inset, y + row + 1, argb);
            context.fill(x + inset, y + height - row - 1, x + width - inset, y + height - row, argb);
        }
    }

    // one offset copy just reads as a second panel, so fake a falloff with a few rings
    public static void shadow(GuiGraphics context, int x, int y, int width, int height, int radius, int argb) {
        int base = (argb >>> 24) & 0xFF;
        int rgb = argb & 0xFFFFFF;

        for (int ring = 3; ring >= 1; ring--) {
            int alpha = Math.max(1, base / (ring + 1));
            draw(context, x - ring, y - ring + 2, width + ring * 2, height + ring * 2, radius + ring, (alpha << 24) | rgb);
        }
    }

    public static void stroke(GuiGraphics context, int x, int y, int width, int height, int radius, int argb) {
        if (width <= 0 || height <= 0) return;

        int r = Math.max(0, Math.min(radius, Math.min(width, height) / 2));

        context.fill(x + r, y, x + width - r, y + 1, argb);
        context.fill(x + r, y + height - 1, x + width - r, y + height, argb);
        context.fill(x, y + r, x + 1, y + height - r, argb);
        context.fill(x + width - 1, y + r, x + width, y + height - r, argb);

        int prev = r;
        for (int row = 0; row < r; row++) {
            double dy = r - row - 0.5;
            int inset = r - (int) Math.round(Math.sqrt(Math.max(0.0, r * r - dy * dy)));

            context.fill(x + inset, y + row, x + prev + 1, y + row + 1, argb);
            context.fill(x + width - prev - 1, y + row, x + width - inset, y + row + 1, argb);
            context.fill(x + inset, y + height - row - 1, x + prev + 1, y + height - row, argb);
            context.fill(x + width - prev - 1, y + height - row - 1, x + width - inset, y + height - row, argb);

            prev = inset;
        }
    }

    public static int withAlpha(int argb, int alpha) {
        int source = (argb >>> 24) & 0xFF;
        int a = Math.min(Math.max(alpha, 0), source == 0 ? 255 : source);
        return (a << 24) | (argb & 0xFFFFFF);
    }

    public static float easeOut(float t) {
        float inverse = 1.0f - t;
        return 1.0f - inverse * inverse * inverse;
    }
}
