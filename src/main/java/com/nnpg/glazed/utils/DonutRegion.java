package com.nnpg.glazed.utils;

import java.util.Locale;

/**
 * Which DonutSMP region you are on. The regions are separate servers with separate worlds, so
 * they have separate seeds: a seed cracked on one predicts nothing on another.
 *
 * There is no packet that says which one you are on, but /rtp is how you get between them, so the
 * region is tracked by watching the command go out. A plain /rtp, or /rtp nether, keeps you where
 * you already are and is deliberately ignored.
 */
public enum DonutRegion {
    UNKNOWN("unknown"),
    ASIA("asia"),
    EAST("east"),
    EU_CENTRAL("eu central"),
    EU_WEST("eu west"),
    OCEANIA("oceania"),
    WEST("west");

    public final String label;

    private static volatile DonutRegion current = UNKNOWN;

    DonutRegion(String label) {
        this.label = label;
    }

    public static DonutRegion current() {
        return current;
    }

    public static void set(DonutRegion region) {
        if (region != null) current = region;
    }

    public static void reset() {
        current = UNKNOWN;
    }

    /** Null when the command does not move you to a different region. */
    public static DonutRegion fromCommand(String command) {
        if (command == null) return null;

        String text = command.trim().toLowerCase(Locale.ROOT);
        if (text.startsWith("/")) text = text.substring(1);
        if (!text.startsWith("rtp")) return null;

        String rest = text.substring(3).trim();
        // plain /rtp stays on this region, /rtp nether and /rtp end only change dimension
        if (rest.isEmpty()) return null;

        return byLabel(rest);
    }

    /** Null when nothing matches, so a typo does not silently pick the wrong seed. */
    public static DonutRegion byLabel(String label) {
        if (label == null) return null;

        String want = label.trim().toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ');

        for (DonutRegion region : values()) {
            if (region != UNKNOWN && region.label.equals(want)) return region;
        }

        return null;
    }

    public static String labels() {
        StringBuilder out = new StringBuilder();

        for (DonutRegion region : values()) {
            if (region == UNKNOWN) continue;
            if (out.length() > 0) out.append(", ");
            out.append(region.label);
        }

        return out.toString();
    }
}
