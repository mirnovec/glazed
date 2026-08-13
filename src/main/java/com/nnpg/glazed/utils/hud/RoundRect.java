package com.nnpg.glazed.utils.hud;

import net.minecraft.client.gui.GuiGraphicsExtractor;

public final class RoundRect {

    private RoundRect() {}

    public static void draw(GuiGraphicsExtractor context, int x, int y, int width, int height, int radius, int argb) {
        if (width <= 0 || height <= 0) return;

        int r = Math.max(0, Math.min(radius, Math.min(width, height) / 2));

        if (r == 0) {
            context.fill(x, y, x + width, y + height, argb);
            return;
        }

        int base = (argb >>> 24) & 0xFF;
        int rgb = argb & 0xFFFFFF;

        context.fill(x, y + r, x + width, y + height - r, argb);
        context.fill(x + r, y, x + width - r, y + r, argb);
        context.fill(x + r, y + height - r, x + width - r, y + height, argb);

        for (int row = 0; row < r; row++) {
            double inset = inset(r, row, r);
            int solid = (int) Math.ceil(inset);
            double partial = solid - inset;

            int topY = y + row;
            int bottomY = y + height - row - 1;

            if (solid < r) {
                context.fill(x + solid, topY, x + r, topY + 1, argb);
                context.fill(x + width - r, topY, x + width - solid, topY + 1, argb);
                context.fill(x + solid, bottomY, x + r, bottomY + 1, argb);
                context.fill(x + width - r, bottomY, x + width - solid, bottomY + 1, argb);
            }

            if (partial <= 0.0 || solid < 1) continue;

            int edge = (int) ((base * partial) + 0.5) << 24 | rgb;

            context.fill(x + solid - 1, topY, x + solid, topY + 1, edge);
            context.fill(x + width - solid, topY, x + width - solid + 1, topY + 1, edge);
            context.fill(x + solid - 1, bottomY, x + solid, bottomY + 1, edge);
            context.fill(x + width - solid, bottomY, x + width - solid + 1, bottomY + 1, edge);
        }
    }

    public static void stroke(GuiGraphicsExtractor context, int x, int y, int width, int height, int radius, int argb) {
        if (width <= 0 || height <= 0) return;

        int r = Math.max(0, Math.min(radius, Math.min(width, height) / 2));
        int base = (argb >>> 24) & 0xFF;
        int rgb = argb & 0xFFFFFF;

        context.fill(x + r, y, x + width - r, y + 1, argb);
        context.fill(x + r, y + height - 1, x + width - r, y + height, argb);
        context.fill(x, y + r, x + 1, y + height - r, argb);
        context.fill(x + width - 1, y + r, x + width, y + height - r, argb);

        for (int row = 0; row < r; row++) {
            double outer = inset(r, row, r);
            double inner = inset(r, row, r - 1);

            int from = (int) Math.ceil(outer);
            int to = Math.max(from, Math.min(r, (int) Math.ceil(inner)));
            double partial = from - outer;

            int topY = y + row;
            int bottomY = y + height - row - 1;

            if (to > from) {
                context.fill(x + from, topY, x + to, topY + 1, argb);
                context.fill(x + width - to, topY, x + width - from, topY + 1, argb);
                context.fill(x + from, bottomY, x + to, bottomY + 1, argb);
                context.fill(x + width - to, bottomY, x + width - from, bottomY + 1, argb);
            }

            if (partial <= 0.0 || from < 1) continue;

            int edge = (int) ((base * partial) + 0.5) << 24 | rgb;

            context.fill(x + from - 1, topY, x + from, topY + 1, edge);
            context.fill(x + width - from, topY, x + width - from + 1, topY + 1, edge);
            context.fill(x + from - 1, bottomY, x + from, bottomY + 1, edge);
            context.fill(x + width - from, bottomY, x + width - from + 1, bottomY + 1, edge);
        }
    }

    private static double inset(int corner, int row, int radius) {
        if (radius <= 0) return corner;

        double dy = corner - row - 0.5;
        double span = radius * radius - dy * dy;

        return span <= 0.0 ? corner : corner - Math.sqrt(span);
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
