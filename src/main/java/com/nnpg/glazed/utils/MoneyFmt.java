package com.nnpg.glazed.utils;

import java.util.Locale;

public final class MoneyFmt {

    private MoneyFmt() {}

    // yup
    public static Double parse(String raw) {
        if (raw == null) return null;

        String text = raw.trim().replace("$", "").replace(",", "").replace(" ", "");
        if (text.isEmpty()) return null;

        double multiplier = 1.0;
        char last = Character.toLowerCase(text.charAt(text.length() - 1));
        if (last == 'k') multiplier = 1_000.0;
        else if (last == 'm') multiplier = 1_000_000.0;
        else if (last == 'b') multiplier = 1_000_000_000.0;

        if (multiplier != 1.0) text = text.substring(0, text.length() - 1);

        try {
            return Double.parseDouble(text) * multiplier;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String format(double value) {
        if (value >= 1_000_000_000.0) return trim(value / 1_000_000_000.0) + "B";
        if (value >= 1_000_000.0) return trim(value / 1_000_000.0) + "M";
        if (value >= 1_000.0) return trim(value / 1_000.0) + "k";
        return trim(value);
    }

    // hmm
    private static String trim(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) return Long.toString((long) value);

        String text = String.format(Locale.ROOT, "%.2f", value);
        if (text.contains(".")) text = text.replaceAll("0+$", "").replaceAll("\\.$", "");
        return text;
    }
}
